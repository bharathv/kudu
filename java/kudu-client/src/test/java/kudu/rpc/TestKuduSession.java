// Copyright (c) 2013, Cloudera, inc.
package kudu.rpc;

import com.google.protobuf.ByteString;
import kudu.ColumnSchema;
import kudu.Schema;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import kudu.tserver.Tserver;
import org.junit.AfterClass;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static kudu.Type.INT32;
import static kudu.Type.STRING;
import static org.junit.Assert.*;

/**
 * This class can either start its own cluster or rely on an existing one.
 * By default it assumes that the master is at localhost:64000.
 * The cluster's configuration flags is found at flagsPath as defined in the pom file.
 * Set startCluster to true in order have the test start the cluster for you.
 * All those properties are set via surefire's systemPropertyVariables, meaning this:
 * $ mvn test -Dstartcluster=false
 * will use an existing cluster at default address found above.
 *
 * The test creates a table with a unique(ish) name which it deletes at the end.
 */
public class TestKuduSession {

  public static final Logger LOG = LoggerFactory.getLogger(TestKuduSession.class);

  private final static String MASTER_ADDRESS = "masterAddress";
  private final static String MASTER_PORT = "masterPort";
  private final static String FLAGS_PATH = "flagsPath";
  private final static String START_CLUSTER = "startCluster";
  private static Process master;
  private static Process tabletServer;
  private static String masterAddress;
  private static int masterPort;
  private static boolean startCluster;
  // Generate a unique table name
  private final static String tableName =
      TestKuduSession.class.getName()+"-"+System.currentTimeMillis();
  private static KuduClient client;
  private static Schema schema = getSchema();
  private static KuduTable table;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    // the following props are set via kudu-client's pom
    masterAddress = System.getProperty(MASTER_ADDRESS);
    masterPort = Integer.parseInt(System.getProperty(MASTER_PORT));
    String flagsPath = System.getProperty(FLAGS_PATH);
    startCluster = Boolean.parseBoolean(System.getProperty(START_CLUSTER));

    if (startCluster) {
      String[] masterCmdLine = {"kudu-master", "--flagfile=" + flagsPath};
      String[] tsCmdLine = {"kudu-tablet_server", masterCmdLine[1]};

      master = Runtime.getRuntime().exec(masterCmdLine);
      Thread.sleep(300);
      tabletServer = Runtime.getRuntime().exec(tsCmdLine);
      // TODO lower than 1000 and we burp a too many retries, fix
      Thread.sleep(1000);
    }

    client = new KuduClient(masterAddress, masterPort);
    table = client.openTable(tableName, schema);

    Deferred<Object> d = client.createTable(tableName, schema);
    final AtomicBoolean gotError = new AtomicBoolean(false);
    d.addErrback(new Callback<Object, Object>() {
      @Override
      public Object call(Object arg) throws Exception {
        gotError.set(true);
        return null;
      }
    });
    try {
      d.join(10000);
    } catch (Exception e) {
      fail("Timed out");
    }
    if (gotError.get()) {
      fail("Got error during table creation, is the Kudu master running at " +
          masterAddress + ":" + masterPort + "?");
    }
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    try {
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
      d.join(2000);
      if (gotError.get()) {
        fail("Couldn't delete a table");
      }
    } finally {
      client.shutdown();
      if (startCluster) {
        master.destroy();
        tabletServer.destroy();
      }
    }
  }

  @Before
  public void setUp() throws Exception {

  }

  @After
  public void tearDown() throws Exception {

  }

  @Test(timeout = 100000)
  public void test() throws Exception {

    KuduSession session = client.newSession(); // using the default, auto-sync
    assertFalse("There shouldn't be any data in the beginning", exists(0));
    Deferred<Object> d = session.apply(createInsert(0));
    d.join();
    assertTrue(exists(0));

    for (int i = 1; i < 10; i++) {
      d = session.apply(createInsert(i));
    }
    d.join();

    assertEquals(10, countInRange(0, 10));

    session.setFlushMode(KuduSession.FlushMode.MANUAL_FLUSH);
    session.setMutationBufferSpace(10);

    d = session.apply(createInsert(10));

    try {
      session.setFlushMode(KuduSession.FlushMode.AUTO_FLUSH_SYNC);
    } catch (IllegalArgumentException ex) {
      /* expected, flush mode remains manual */
    }

    assertFalse(exists(10));

    for (int i = 11; i < 20; i++) {
      d = session.apply(createInsert(i));
    }

    assertEquals(0, countInRange(10, 20));
    try {
      session.apply(createInsert(20));
    } catch (NonRecoverableException ex) {
      /* expected, buffer would be too big */
    }
    assertEquals(0, countInRange(10, 20)); // the buffer should still be full

    session.flush();
    d.join(); // d is from the last good insert eg 20
    assertEquals(10, countInRange(10, 20)); // now everything should be there

    session.setFlushMode(KuduSession.FlushMode.AUTO_FLUSH_BACKGROUND);

    d = session.apply(createInsert(20));
    Thread.sleep(50); // waiting a minimal amount of time to make sure the interval is in effect
    assertFalse(exists(20));
    // Add 10 items, the last one will stay in the buffer
    for (int i = 21; i < 30; i++) {
      d = session.apply(createInsert(i));
    }
    Deferred<Object> buffered = session.apply(createInsert(30));
    long now = System.currentTimeMillis();
    d.join();
    // auto flush will force flush if the buffer is full as it should be now
    // so we check that we didn't wait the full interval
    long elapsed = System.currentTimeMillis() - now;
    assertTrue(elapsed < 950);
    assertEquals(10, countInRange(20, 31));
    buffered.join();
    assertEquals(11, countInRange(20, 31));

    session.setFlushMode(KuduSession.FlushMode.AUTO_FLUSH_SYNC);
    Update update = createUpdate(30);
    update.addInt(schema.getColumn(2).getName(), 999);
    update.addString(schema.getColumn(3).getName(), "updated data");
    d = session.apply(update);
    d.addErrback(defaultErrorCB);
    d.join();
    assertEquals(31, countInRange(0, 31));

    Delete del = createDelete(30);
    d = session.apply(del);
    d.addErrback(defaultErrorCB);
    d.join();
    assertEquals(30, countInRange(0, 31));

    session.setFlushMode(KuduSession.FlushMode.MANUAL_FLUSH);
    session.setMutationBufferSpace(35);
    for (int i = 0; i < 20; i++) {
      buffered = session.apply(createDelete(i));
    }
    assertEquals(30, countInRange(0, 31));
    session.flush();
    buffered.join();
    assertEquals(10, countInRange(0, 31));

    for (int i = 30; i < 40; i++) {
      session.apply(createInsert(i));
    }

    for (int i = 20; i < 30; i++) {
      buffered = session.apply(createDelete(i));
    }

    assertEquals(10, countInRange(0, 40));
    session.flush();
    buffered.join(2000);
    assertEquals(10, countInRange(0, 40));

  }

  public static Insert createInsert(int key) {
    Insert insert = table.newInsert();
    insert.addInt(schema.getColumn(0).getName(), key);
    insert.addInt(schema.getColumn(1).getName(), 2);
    insert.addInt(schema.getColumn(2).getName(), 3);
    insert.addString(schema.getColumn(3).getName(), "a string");
    return insert;
  }

  public static Update createUpdate(int key) {

    Update update = table.newUpdate();
    update.addInt(schema.getColumn(0).getName(), key);
    return update;
  }

  public static Delete createDelete(int key) {
    Delete delete = table.newDelete();
    delete.addInt(schema.getColumn(0).getName(), key);
    return delete;
  }

  public static boolean exists(final int key) throws Exception {

    KuduScanner scanner = getScanner(key, key);
    final AtomicBoolean exists = new AtomicBoolean(false);

    Callback<Object, KuduScanner.RowResultIterator> cb =
        new Callback<Object, KuduScanner.RowResultIterator>() {
      @Override
      public Object call(KuduScanner.RowResultIterator arg) throws Exception {
        if (arg == null) return null;
        RowResult row;
        while (arg.hasNext()) {
          row = arg.next();
          //System.out.println("Seeing " + row.toStringLongFormat());
          if (row.getInt(0) == key) {
            exists.set(true);
            break;
          }
        }
        return null;
      }
    };

    while (scanner.hasMoreRows()) {
      Deferred<KuduScanner.RowResultIterator> data = scanner.nextRows();
      data.addCallbacks(cb, defaultErrorCB);
      data.join();
      if (exists.get()) {
        break;
      }
    }

    Deferred<KuduScanner.RowResultIterator> closer = scanner.close();
    closer.join();
    return exists.get();
  }

  public static int countInRange(final int startOrder, final int endOrder) throws Exception {

    KuduScanner scanner = getScanner(startOrder, endOrder);
    final AtomicInteger counter = new AtomicInteger();

    Callback<Object, KuduScanner.RowResultIterator> cb = new Callback<Object, KuduScanner.RowResultIterator>() {
      @Override
      public Object call(KuduScanner.RowResultIterator arg) throws Exception {
        if (arg == null) return null;
        RowResult row;
        while (arg.hasNext()) {
          row = arg.next();
          //System.out.println("Seeing " + row.toStringLongFormat());
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

  private static KuduScanner getScanner(int start, int end) {
    KuduScanner scanner = client.newScanner(table);
    scanner.setSchema(schema);
    Tserver.ColumnRangePredicatePB.Builder builder = Tserver.ColumnRangePredicatePB.newBuilder();
    builder.setColumn(ProtobufHelper.columnToPb(schema.getColumn(0)));

    builder.setLowerBound(ByteString.copyFrom(Bytes.fromInt(start)));
    builder.setUpperBound(ByteString.copyFrom(Bytes.fromInt(end)));
    scanner.addColumnRangePredicate(builder.build());
    return scanner;
  }

  public static Schema getSchema() {
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
}