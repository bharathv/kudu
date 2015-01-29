// Copyright (c) 2015, Cloudera, inc.
// Confidential Cloudera Information: Covered by NDA.
package kudu.rpc;

import com.google.common.net.HostAndPort;
import com.google.protobuf.ByteString;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import kudu.master.Master;
import kudu.metadata.Metadata;
import kudu.util.NetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class grouping the callback and the errback for GetMasterRegistration calls
 * made in getMasterTableLocationsPB.
 */
final class GetMasterRegistrationReceived {

  private static final Logger LOG = LoggerFactory.getLogger(GetMasterRegistrationReceived.class);

  private final List<HostAndPort> masterAddrs;
  private final Deferred<Master.GetTableLocationsResponsePB> responseD;
  private final int numMasters;

  // Used to avoid calling 'responseD' twice.
  private final AtomicBoolean responseDCalled = new AtomicBoolean(false);

  // Number of responses we've receives: used to tell whether or not we've received
  // errors/replies from all of the masters, or if there are any
  // GetMasterRegistrationRequests still pending.
  private final AtomicInteger countResponsesReceived = new AtomicInteger(0);

  // Exceptions received so far: kept for debugging purposes.
  // (see: NoLeaderMasterFoundException#create() for how this is used).
  private final List<Exception> exceptionsReceived =
      Collections.synchronizedList(new ArrayList<Exception>());

  /**
   * Creates an object that holds the state needed to retrieve master table's location.
   * @param masterAddrs Addresses of all master replicas that we want to retrieve the
   *                    registration from.
   * @param responseD Deferred object that will hold the GetTableLocationsResponsePB object for
   *                  the master table.
   */
  public GetMasterRegistrationReceived(List<HostAndPort> masterAddrs,
                                       Deferred<Master.GetTableLocationsResponsePB> responseD) {
    this.masterAddrs = masterAddrs;
    this.responseD = responseD;
    this.numMasters = masterAddrs.size();
  }

  /**
   * Creates a callback for a GetMasterRegistrationRequest that was sent to 'hostAndPort'.
   * @see GetMasterRegistrationCB
   * @param hostAndPort Host and part for the RPC we're attaching this to. Host and port must
   *                    be valid.
   * @return The callback object that can be added to the RPC request.
   */
  public Callback<Void, GetMasterRegistrationResponse> callbackForNode(HostAndPort hostAndPort) {
    return new GetMasterRegistrationCB(hostAndPort);
  }

  /**
   * Creates an errback for a GetMasterRegistrationRequest that was sent to 'hostAndPort'.
   * @see GetMasterRegistrationErrCB
   * @param hostAndPort Host and port for the RPC we're attaching this to. Used for debugging
   *                    purposes.
   * @return The errback object that can be added to the RPC request.
   */
  public Callback<Void, Exception> errbackForNode(HostAndPort hostAndPort) {
    return new GetMasterRegistrationErrCB(hostAndPort);
  }

  /**
   * Checks if we've already received a response or an exception from every master that
   * we've sent a GetMasterRegistrationRequest to. If so -- and no leader has been found
   * (that is, 'responseD' was never called) -- pass a {@link NoLeaderMasterFoundException}
   * to responseD.
   */
  private void incrementCountAndCheckExhausted() {
    if (countResponsesReceived.incrementAndGet() == numMasters) {
      if (responseDCalled.compareAndSet(false, true)) {
        String allHosts = NetUtil.hostsAndPortsToString(masterAddrs);
        LOG.warn("Unable to find the leader of the master quorum(" + allHosts + ").");
        responseD.callback(NoLeaderMasterFoundException.create(
            "Master quorum (" + allHosts + ") has no leader.",
            exceptionsReceived));
      }
    }
  }

  /**
   * Callback for each GetMasterRegistrationRequest sent in getMasterTableLocations() above.
   * If a request (paired to a specific master) returns a reply that indicates it's a leader,
   * the callback in 'responseD' is invoked with an initialized GetTableLocationResponsePB
   * object containing the leader's RPC address.
   * If the master is not a leader, increment 'countResponsesReceived': if the count equals to
   * the number of masters, pass {@link NoLeaderMasterFoundException} into
   * 'responseD' if no one else had called 'responseD' before; otherwise, do nothing.
   */
  final class GetMasterRegistrationCB implements Callback<Void, GetMasterRegistrationResponse> {
    private final HostAndPort hostAndPort;

    public GetMasterRegistrationCB(HostAndPort hostAndPort) {
      this.hostAndPort = hostAndPort;
    }

    @Override
    public Void call(GetMasterRegistrationResponse r) throws Exception {
      Master.TabletLocationsPB.ReplicaPB.Builder replicaBuilder = Master.TabletLocationsPB
          .ReplicaPB.newBuilder();

      Master.TSInfoPB.Builder tsInfoBuilder = Master.TSInfoPB.newBuilder();
      tsInfoBuilder.addRpcAddresses(ProtobufHelper.hostAndPortToPB(hostAndPort));
      tsInfoBuilder.setPermanentUuid(r.getInstanceId().getPermanentUuid());
      replicaBuilder.setTsInfo(tsInfoBuilder);
      if (r.getRole().equals(Metadata.QuorumPeerPB.Role.LEADER)) {
        replicaBuilder.setRole(r.getRole());
        Master.TabletLocationsPB.Builder locationBuilder = Master.TabletLocationsPB.newBuilder();
        locationBuilder.setStartKey(ByteString.copyFromUtf8(""));
        locationBuilder.setEndKey(ByteString.copyFromUtf8(""));
        locationBuilder.setTabletId(ByteString.copyFromUtf8(KuduClient.MASTER_TABLE_HACK));
        locationBuilder.setStale(false);
        locationBuilder.addReplicas(replicaBuilder);
        // No one else has called this before us.
        if (responseDCalled.compareAndSet(false, true)) {
          responseD.callback(
              Master.GetTableLocationsResponsePB.newBuilder().addTabletLocations(
                  locationBuilder.build()).build()
          );
        } else {
          LOG.debug("Callback already invoked, discarding response(" + r.toString() + ") from " +
              hostAndPort.toString());
        }
      } else {
        incrementCountAndCheckExhausted();
      }
      return null;
    }

    @Override
    public String toString() {
      return "get master registration for " + hostAndPort.toString();
    }
  }

  /**
   * Errback for each GetMasterRegistrationRequest sent in getMasterTableLocations() above.
   * Stores each exception in 'exceptionsReceived'. Increments 'countResponseReceived': if
   * the count is equal to the number of masters and no one else had called 'responseD' before,
   * pass a {@link NoLeaderMasterFoundException} into 'responseD'; otherwise, do
   * nothing.
   */
  final class GetMasterRegistrationErrCB implements Callback<Void, Exception> {
    private final HostAndPort hostAndPort;

    public GetMasterRegistrationErrCB(HostAndPort hostAndPort) {
      this.hostAndPort = hostAndPort;
    }

    @Override
    public Void call(Exception e) throws Exception {
      LOG.warn("Error receiving a response from: " + hostAndPort, e);
      exceptionsReceived.add(e);
      incrementCountAndCheckExhausted();
      return null;
    }

    @Override
    public String toString() {
      return "get master registration errback for " + hostAndPort.toString();
    }
  }
}
