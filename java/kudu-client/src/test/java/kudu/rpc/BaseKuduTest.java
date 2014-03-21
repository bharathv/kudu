// Copyright (c) 2014, Cloudera, inc.
package kudu.rpc;

import com.google.common.base.Stopwatch;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import kudu.ColumnSchema;
import kudu.Schema;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static kudu.Type.INT32;
import static kudu.Type.STRING;
import static org.junit.Assert.fail;

public class BaseKuduTest {

  public static final Logger LOG = LoggerFactory.getLogger(BaseKuduTest.class);

  private final static String MASTER_ADDRESS = "masterAddress";
  private final static String MASTER_PORT = "masterPort";
  private final static String FLAGS_PATH = "flagsPath";
  private final static String BASE_DIR_PATH = "baseDirPath";
  private final static String START_CLUSTER = "startCluster";
  private static boolean startCluster;
  static String masterAddress;
  static int masterPort;

  static final int DEFAULT_SLEEP = 10000;
  static final List<Thread> processInputPrinters = new ArrayList<Thread>();
  static Process master;
  static Process tabletServer;
  static KuduClient client;

  private static List<String> tableNames = new ArrayList<String>();

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    // the following props are set via kudu-client's pom
    masterAddress = System.getProperty(MASTER_ADDRESS);
    masterPort = Integer.parseInt(System.getProperty(MASTER_PORT));
    String flagsPath = System.getProperty(FLAGS_PATH);
    String baseDirPath = System.getProperty(BASE_DIR_PATH);
    startCluster = Boolean.parseBoolean(System.getProperty(START_CLUSTER));

    if (startCluster) {
      String flagFileOpt = "--flagfile=" + flagsPath;
      long now = System.currentTimeMillis();
      String[] masterCmdLine = {"kudu-master", flagFileOpt, "--master_base_dir=" + baseDirPath
          + "/master-" + now};
      String[] tsCmdLine = {"kudu-tablet_server", flagFileOpt, "--tablet_server_base_dir=" + baseDirPath
          + "/ts-" + now};

      master = configureAndStartProcess(masterCmdLine);
      Thread.sleep(300);
      tabletServer = configureAndStartProcess(tsCmdLine);
    }

    client = new KuduClient(masterAddress, masterPort);
    if (!waitForTabletServers(1)) {
      fail("Couldn't even get a TS running, aborting");
    }
  }

  /**
   * Wait up to DEFAULT_SLEEP for an expected count of TS to connect to the master
   * @param expected How many TS are expected
   * @return true if there are at least as many TS as expected, otherwise false
   */
  static boolean waitForTabletServers(int expected) throws Exception {
    int count = 0;
    Stopwatch stopwatch = new Stopwatch().start();
    while (count < expected && stopwatch.elapsedMillis() < DEFAULT_SLEEP) {
      Thread.sleep(200);
      Deferred<Object> d = client.getTabletServersCount();
      d.addErrback(defaultErrorCB);
      count = (Integer)d.join(DEFAULT_SLEEP);
    }
    return count >= expected;
  }

  /**
   * Starts a process using the provided command and configures it to be daemon,
   * redirects the stderr to stdout, and starts a thread that will read from the process' input
   * stream and redirect that to LOG.
   * @param command Process and options
   * @return The started process
   * @throws IOException Exception if an error prevents from starting the process (unrelated to
   * in-process errors like if a data folder cannot be found).
   */
  static Process configureAndStartProcess(String[] command) throws IOException {
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    Process proc = processBuilder.start();
    ProcessInputStreamLogPrinterRunnable printer =
        new ProcessInputStreamLogPrinterRunnable(proc.getInputStream());
    Thread thread = new Thread(printer);
    thread.setDaemon(true);
    thread.setName(command[0]);
    processInputPrinters.add(thread);
    thread.start();
    return proc;
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    try {
      for (String tableName : tableNames) {
        final AtomicBoolean gotError = new AtomicBoolean(false);
        Deferred<Object> d = client.deleteTable(tableName);
        d.addErrback(new Callback<Object, Object>() {
          @Override
          public Object call(Object arg) throws Exception {
            LOG.warn("tearDown errback " + arg);
            gotError.set(true);
            return null;
          }
        });
        d.join(DEFAULT_SLEEP);
        if (gotError.get()) {
          fail("Couldn't delete a table");
        }
      }
    } finally {
      client.shutdown();
      if (startCluster) {
        master.destroy();
        tabletServer.destroy();
        for (Thread thread : processInputPrinters) {
          thread.interrupt();
        }
      }
    }
  }

  static void createTable(String tableName, Schema schema, CreateTableBuilder builder) {
    Deferred<Object> d = client.createTable(tableName, schema, builder);
    final AtomicBoolean gotError = new AtomicBoolean(false);
    d.addErrback(new Callback<Object, Object>() {
      @Override
      public Object call(Object arg) throws Exception {
        gotError.set(true);
        return null;
      }
    });
    try {
      d.join(DEFAULT_SLEEP);
    } catch (Exception e) {
      fail("Timed out");
    }
    if (gotError.get()) {
      fail("Got error during table creation, is the Kudu master running at " +
          masterAddress + ":" + masterPort + "?");
    }
    tableNames.add(tableName);
  }

  static int countRowsInScan(KuduScanner scanner) throws Exception{
    final AtomicInteger counter = new AtomicInteger();

    Callback<Object, KuduScanner.RowResultIterator> cb = new Callback<Object, KuduScanner.RowResultIterator>() {
      @Override
      public Object call(KuduScanner.RowResultIterator arg) throws Exception {
        if (arg == null) return null;
        RowResult row;
        while (arg.hasNext()) {
          row = arg.next();
          counter.incrementAndGet();
        }
        return null;
      }
    };

    while (scanner.hasMoreRows()) {
      Deferred<KuduScanner.RowResultIterator> data = scanner.nextRows();
      data.addCallbacks(cb, defaultErrorCB);
      data.join();
    }

    Deferred<KuduScanner.RowResultIterator> closer = scanner.close();
    closer.addCallbacks(cb, defaultErrorCB);
    closer.join();
    return counter.get();
  }

  public static Schema getBasicSchema() {
    ArrayList<ColumnSchema> columns = new ArrayList<ColumnSchema>(4);
    columns.add(new ColumnSchema("key", INT32, true));
    columns.add(new ColumnSchema("column1_i", INT32));
    columns.add(new ColumnSchema("column2_i", INT32));
    columns.add(new ColumnSchema("column3_s", STRING));
    return new Schema(columns);
  }

  static Callback<Object, Object> defaultErrorCB = new Callback<Object, Object>() {
    @Override
    public Object call(Object arg) throws Exception {
      if (arg == null) return null;
      if (arg instanceof Exception) {
        LOG.warn("Got exception", (Exception) arg);
      } else {
        LOG.warn("Got an error response back " + arg);
      }
      return null;
    }
  };

  /**
   * Helper runnable that can log what the processes are sending on their stdout and stderr that
   * we'd otherwise miss.
   */
  static class ProcessInputStreamLogPrinterRunnable implements Runnable {

    private final InputStream is;

    public ProcessInputStreamLogPrinterRunnable(InputStream is) {
      this.is = is;
    }

    @Override
    public void run() {
      try {
        String line;
        BufferedReader in = new BufferedReader(new InputStreamReader(is));
        while ((line = in.readLine()) != null) {
          LOG.info(line);
        }
        in.close();
      }
      catch (Exception e) {
        if (!e.getMessage().contains("Stream closed")) {
          LOG.error("Caught error while reading a process' output", e);
        }
      }
    }
  }

}