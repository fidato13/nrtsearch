/*
 * Copyright 2020 Yelp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yelp.nrtsearch.server.luceneserver;

import com.yelp.nrtsearch.server.grpc.FilesMetadata;
import com.yelp.nrtsearch.server.grpc.ReplicationServerClient;
import com.yelp.nrtsearch.server.grpc.TransferStatus;
import com.yelp.nrtsearch.server.luceneserver.index.IndexStateManager;
import com.yelp.nrtsearch.server.luceneserver.nrt.NrtDataManager;
import com.yelp.nrtsearch.server.luceneserver.nrt.RefreshUploadFuture;
import com.yelp.nrtsearch.server.monitoring.NrtMetrics;
import com.yelp.nrtsearch.server.utils.HostPort;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.replicator.nrt.CopyState;
import org.apache.lucene.replicator.nrt.FileMetaData;
import org.apache.lucene.replicator.nrt.PrimaryNode;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.util.ThreadInterruptedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NRTPrimaryNode extends PrimaryNode {
  private static final Logger logger = LoggerFactory.getLogger(NRTPrimaryNode.class);
  private final HostPort hostPort;
  private final String indexName;
  private final IndexStateManager indexStateManager;
  private final NrtDataManager nrtDataManager;
  final List<MergePreCopy> warmingSegments = Collections.synchronizedList(new ArrayList<>());
  final Queue<ReplicaDetails> replicasInfos = new ConcurrentLinkedQueue<>();

  public NRTPrimaryNode(
      IndexStateManager indexStateManager,
      HostPort hostPort,
      IndexWriter writer,
      int id,
      long primaryGen,
      long forcePrimaryVersion,
      NrtDataManager nrtDataManager,
      SearcherFactory searcherFactory,
      PrintStream printStream)
      throws IOException {
    super(writer, id, primaryGen, forcePrimaryVersion, searcherFactory, printStream);
    this.hostPort = hostPort;
    this.indexName = indexStateManager.getCurrent().getName();
    this.indexStateManager = indexStateManager;
    this.nrtDataManager = nrtDataManager;
  }

  /**
   * Get the {@link NrtDataManager} for this primary node.
   *
   * @return the {@link NrtDataManager} for this primary node
   */
  public NrtDataManager getNrtDataManager() {
    return nrtDataManager;
  }

  public static class ReplicaDetails {
    private final int replicaId;
    private final HostPort hostPort;
    private final ReplicationServerClient replicationServerClient;

    public int getReplicaId() {
      return replicaId;
    }

    public ReplicationServerClient getReplicationServerClient() {
      return replicationServerClient;
    }

    public HostPort getHostPort() {
      return hostPort;
    }

    ReplicaDetails(int replicaId, ReplicationServerClient replicationServerClient) {
      this.replicaId = replicaId;
      this.replicationServerClient = replicationServerClient;
      this.hostPort =
          new HostPort(replicationServerClient.getHost(), replicationServerClient.getPort());
    }

    /*
     * WARNING: Do not replace this with the IDE autogenerated equals method. We put this object in a ConcurrentQueue
     * and this is the check that we need for equality.
     * */
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ReplicaDetails that = (ReplicaDetails) o;
      return replicaId == that.replicaId && Objects.equals(hostPort, that.hostPort);
    }

    /*
     * WARNING: Do not replace this with the IDE autogenerated hashCode method. We put this object in a ConcurrentQueue
     * and this is the check that we need for hashCode.
     * */
    @Override
    public int hashCode() {
      return Objects.hash(replicaId, hostPort);
    }
  }

  /** Holds all replicas currently warming (pre-copying the new files) a single merged segment */
  static class MergePreCopy {
    final Set<ReplicationServerClient> connections = Collections.synchronizedSet(new HashSet<>());
    final Map<ReplicationServerClient, Iterator<TransferStatus>> clientToTransferStatusIterator;
    final Map<String, FileMetaData> files;
    private final Deadline deadline;
    private boolean finished;

    public MergePreCopy(
        Map<String, FileMetaData> files,
        Map<ReplicationServerClient, Iterator<TransferStatus>> clientToTransferStatusIterator,
        Deadline deadline) {
      this.files = files;
      this.clientToTransferStatusIterator = new ConcurrentHashMap<>(clientToTransferStatusIterator);
      this.connections.addAll(clientToTransferStatusIterator.keySet());
      this.deadline = deadline;
    }

    public synchronized boolean tryAddConnection(
        ReplicationServerClient c,
        String indexName,
        String indexId,
        long primaryGen,
        FilesMetadata filesMetadata) {
      if (!finished && (deadline == null || !deadline.isExpired())) {
        Iterator<TransferStatus> transferStatusIterator =
            c.copyFiles(indexName, indexId, primaryGen, filesMetadata, deadline);
        clientToTransferStatusIterator.put(c, transferStatusIterator);
        connections.add(c);
        return true;
      } else {
        return false;
      }
    }

    public synchronized boolean finished() {
      if (connections.isEmpty()) {
        finished = true;
        return true;
      } else {
        return false;
      }
    }

    Iterator<TransferStatus> getTransferStatusIteratorForClient(ReplicationServerClient client) {
      return clientToTransferStatusIterator.get(client);
    }
  }

  private long getCurrentMaxMergePreCopyDurationSec() {
    return indexStateManager.getCurrent().getMaxMergePreCopyDurationSec();
  }

  void sendNewNRTPointToReplicas() {
    logger.info("NRTPrimaryNode: sendNRTPoint");
    // Something did get flushed (there were indexing ops since the last flush):

    // nocommit: we used to notify caller of the version, before trying to push to replicas, in case
    // we crash after flushing but
    // before notifying all replicas, at which point we have a newer version index than client knew
    // about?
    long version = getCopyStateVersion();
    logMessage("send flushed version=" + version + " replica count " + replicasInfos.size());
    NrtMetrics.searcherVersion.labels(indexName).set(version);
    NrtMetrics.nrtPrimaryPointCount.labels(indexName).inc();

    // Notify current replicas:
    Iterator<ReplicaDetails> it = replicasInfos.iterator();
    while (it.hasNext()) {
      ReplicaDetails replicaDetails = it.next();
      int replicaID = replicaDetails.replicaId;
      ReplicationServerClient currentReplicaServerClient = replicaDetails.replicationServerClient;
      try {
        currentReplicaServerClient.newNRTPoint(
            indexName, indexStateManager.getIndexId(), primaryGen, version);
      } catch (StatusRuntimeException e) {
        Status status = e.getStatus();
        if (status.getCode().equals(Status.UNAVAILABLE.getCode())) {
          logger.warn(
              "NRTPRimaryNode: sendNRTPoint, lost connection to replicaId: {} host: {} port: {}",
              replicaDetails.replicaId,
              replicaDetails.replicationServerClient.getHost(),
              replicaDetails.replicationServerClient.getPort());
          currentReplicaServerClient.close();
          it.remove();
        } else if (status.getCode().equals(Status.FAILED_PRECONDITION.getCode())) {
          logger.warn(
              "NRTPRimaryNode: sendNRTPoint, replicaId: {} host: {} port: {} cannot process nrt point, closing connection",
              replicaDetails.replicaId,
              replicaDetails.replicationServerClient.getHost(),
              replicaDetails.replicationServerClient.getPort());
          currentReplicaServerClient.close();
          it.remove();
        }
      } catch (Exception e) {
        String msg =
            String.format(
                "top: failed to connect R%d for newNRTPoint; skipping: %s",
                replicaID, e.getMessage());
        message(msg);
        logger.warn(msg);
      }
    }
  }

  // TODO: awkward we are forced to do this here ... this should really live in replicator code,
  // e.g. PrimaryNode.mgr should be this:
  static class PrimaryNodeReferenceManager extends ReferenceManager<IndexSearcher> {
    final NRTPrimaryNode primary;
    final SearcherFactory searcherFactory;
    List<RefreshUploadFuture> nextRefreshWatchers = new ArrayList<>();

    public PrimaryNodeReferenceManager(NRTPrimaryNode primary, SearcherFactory searcherFactory)
        throws IOException {
      this.primary = primary;
      this.searcherFactory = searcherFactory;
      current =
          SearcherManager.getSearcher(
              searcherFactory, primary.getSearcherManager().acquire().getIndexReader(), null);
    }

    /**
     * Get a future that will be notified when the next refresh is durable in the remote backend.
     *
     * @return refresh future
     */
    public synchronized Future<?> nextRefreshDurable() {
      RefreshUploadFuture future = new RefreshUploadFuture();
      nextRefreshWatchers.add(future);
      return future;
    }

    @Override
    protected void decRef(IndexSearcher reference) throws IOException {
      reference.getIndexReader().decRef();
    }

    @Override
    protected IndexSearcher refreshIfNeeded(IndexSearcher referenceToRefresh) throws IOException {
      // get watchers waiting for a durable refresh
      List<RefreshUploadFuture> watchers;
      synchronized (this) {
        watchers = nextRefreshWatchers;
        nextRefreshWatchers = new ArrayList<>();
      }

      boolean uploadQueued = false;
      try {
        if (primary.flushAndRefresh()) {
          if (!watchers.isEmpty()) {
            // queue the index upload if there are watchers waiting for a durable refresh
            queueIndexUpload(watchers);
            uploadQueued = true;
          }
          primary.sendNewNRTPointToReplicas();
          // NOTE: steals a ref from one ReferenceManager to another!
          return SearcherManager.getSearcher(
              searcherFactory,
              primary.getSearcherManager().acquire().getIndexReader(),
              referenceToRefresh.getIndexReader());
        } else {
          if (!watchers.isEmpty()) {
            // even if flush was a noop, we still need to make sure the data is uploaded
            queueIndexUpload(watchers);
            uploadQueued = true;
          }
          return null;
        }
      } catch (Throwable t) {
        // We failed before adding the upload task to the queue, so we must notify the watchers here
        if (!uploadQueued) {
          for (RefreshUploadFuture watcher : watchers) {
            watcher.setDone(t);
          }
        }
        throw t;
      }
    }

    private void queueIndexUpload(List<RefreshUploadFuture> watchers) throws IOException {
      CopyState copyState = primary.getCopyState();
      primary.getNrtDataManager().enqueueUpload(copyState, watchers);
    }

    @Override
    protected boolean tryIncRef(IndexSearcher reference) {
      return reference.getIndexReader().tryIncRef();
    }

    @Override
    protected int getRefCount(IndexSearcher reference) {
      return reference.getIndexReader().getRefCount();
    }
  }

  @Override
  protected void preCopyMergedSegmentFiles(
      SegmentCommitInfo info, Map<String, FileMetaData> files) {
    long mergeStartNS = System.nanoTime();
    if (replicasInfos.isEmpty()) {
      logMessage("no replicas, skip warming " + info);
      return;
    }

    NrtMetrics.nrtMergeCopyStartCount.labels(indexName).inc();

    long maxMergePreCopyDurationSec = getCurrentMaxMergePreCopyDurationSec();
    Deadline deadline;
    if (maxMergePreCopyDurationSec > 0) {
      deadline = Deadline.after(maxMergePreCopyDurationSec, TimeUnit.SECONDS);
    } else {
      deadline = null;
    }
    MergePreCopy preCopy;
    synchronized (warmingSegments) {
      logMessage(
          String.format(
              "Start merge precopy %s to %d replicas; localAddress=%s: files=%s",
              info, replicasInfos.size(), hostPort, files.keySet()));

      Map<ReplicationServerClient, Iterator<TransferStatus>> allCopyStatus =
          callCopyFilesForPreCopy(files, deadline);
      preCopy = new MergePreCopy(files, allCopyStatus, deadline);
      warmingSegments.add(preCopy);
    }

    try {

      long startNS = System.nanoTime();
      long lastWarnNS = startNS;

      // TODO: maybe ... place some sort of time limit on how long we are willing to wait for slow
      // replica(s) to finish copying?
      while (preCopy.finished() == false) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException ie) {
          throw new ThreadInterruptedException(ie);
        }

        if (isClosed()) {
          logMessage("Primary is closing: cancel merge precopy");
          // Connections are closed in close() method
          return;
        }

        long ns = System.nanoTime();
        if (ns - lastWarnNS > 1000000000L) {
          logMessage(
              String.format(
                  Locale.ROOT,
                  "Warning: still warming merge %s to %d replicas for %.1f sec...",
                  info,
                  preCopy.connections.size(),
                  (ns - startNS) / 1000000000.0));
          lastWarnNS = ns;
        }

        // Because a replica can suddenly start up and "join" into this merge pre-copy:
        List<ReplicationServerClient> currentConnections = new ArrayList<>(preCopy.connections);
        for (ReplicationServerClient currentReplicationServerClient : currentConnections) {
          try {
            Iterator<TransferStatus> transferStatusIterator =
                preCopy.getTransferStatusIteratorForClient(currentReplicationServerClient);
            while (transferStatusIterator.hasNext()) {
              TransferStatus transferStatus = transferStatusIterator.next();
              logger.debug(
                  "transferStatus for replicationServerClient={},  merge files={}, code={}, message={}",
                  currentReplicationServerClient,
                  files.keySet(),
                  transferStatus.getCode(),
                  transferStatus.getMessage());
            }
          } catch (Throwable t) {
            String msg =
                String.format(
                    "Ignore exception trying to read byte during merge precopy for segment=%s to replica=%s: %s files=%s",
                    info, currentReplicationServerClient, t, files.keySet());
            logger.warn(msg, t);
            super.message(msg);
          }
        }
        currentConnections.forEach(preCopy.connections::remove);
      }
      logMessage("Done merge precopy " + info);
    } finally {
      warmingSegments.remove(preCopy);

      // record metrics for this merge
      NrtMetrics.nrtPrimaryMergeTime
          .labels(indexName)
          .observe((System.nanoTime() - mergeStartNS) / 1000000.0);
      NrtMetrics.nrtMergeCopyEndCount.labels(indexName).inc();
    }
  }

  private Map<ReplicationServerClient, Iterator<TransferStatus>> callCopyFilesForPreCopy(
      Map<String, FileMetaData> files, Deadline deadline) {
    // Ask all currently known replicas to pre-copy this newly merged segment's files:
    Iterator<ReplicaDetails> replicaInfos = replicasInfos.iterator();
    ReplicaDetails replicaDetails = null;
    FilesMetadata filesMetadata = RecvCopyStateHandler.writeFilesMetaData(files);
    Map<ReplicationServerClient, Iterator<TransferStatus>> allCopyStatus =
        new ConcurrentHashMap<>();
    while (replicaInfos.hasNext()) {
      try {
        replicaDetails = replicaInfos.next();
        Iterator<TransferStatus> copyStatus =
            replicaDetails.replicationServerClient.copyFiles(
                indexName, indexStateManager.getIndexId(), primaryGen, filesMetadata, deadline);
        allCopyStatus.put(replicaDetails.replicationServerClient, copyStatus);
        logMessage(
            String.format(
                "Start precopying merged segments for replica %s:%d",
                replicaDetails.replicationServerClient.getHost(),
                replicaDetails.replicationServerClient.getPort()));
      } catch (Throwable t) {
        logMessage(
            String.format(
                "Ignore merge precopy exception for replica host:%s port: %d: %s",
                replicaDetails.replicationServerClient.getHost(),
                replicaDetails.replicationServerClient.getPort(),
                t));
      }
    }
    return allCopyStatus;
  }

  public void logMessage(String msg) {
    message(msg);
    logger.info(msg);
  }

  public void setRAMBufferSizeMB(double mb) {
    writer.getConfig().setRAMBufferSizeMB(mb);
  }

  public void addReplica(int replicaID, ReplicationServerClient replicationServerClient)
      throws IOException {
    logMessage("add replica: " + warmingSegments.size() + " current warming merges ");
    ReplicaDetails replicaDetails = new ReplicaDetails(replicaID, replicationServerClient);
    if (!replicasInfos.contains(replicaDetails)) {
      replicasInfos.add(replicaDetails);
    }
    // Step through all currently warming segments and try to add this replica if it isn't there
    // already:
    synchronized (warmingSegments) {
      for (MergePreCopy preCopy : warmingSegments) {
        logger.debug("warming segment {}", preCopy.files.keySet());
        message("warming segment " + preCopy.files.keySet());
        if (preCopy.connections.contains(replicationServerClient)) {
          logMessage(
              String.format(
                  "Replica %s:%d is already warming this segment",
                  replicaDetails.replicationServerClient.getHost(),
                  replicaDetails.replicationServerClient.getPort()));
          // It's possible (maybe) that the replica started up, then a merge kicked off, and it
          // warmed to this new replica, all before the
          // replica sent us this command:
          continue;
        }

        // Start copying the segments to the replica
        FilesMetadata filesMetadata = RecvCopyStateHandler.writeFilesMetaData(preCopy.files);

        // If the preCopy is still in progress add this transfer to it as well
        if (preCopy.tryAddConnection(
            replicationServerClient,
            indexName,
            indexStateManager.getIndexId(),
            primaryGen,
            filesMetadata)) {
          logMessage(
              String.format(
                  "Start precopying merged segments for new replica %s:%d",
                  replicaDetails.replicationServerClient.getHost(),
                  replicaDetails.replicationServerClient.getPort()));
        } else {
          // This can happen, if all other replicas just now finished warming this segment, and so
          // we were just a bit too late.  In this case the segment must be copied over in the next
          // nrt point sent to this replica
          logMessage(
              String.format(
                  "Merge precopy already completed, unable to add new replica %s:%d",
                  replicaDetails.replicationServerClient.getHost(),
                  replicaDetails.replicationServerClient.getPort()));
        }
      }
    }
  }

  public Collection<ReplicaDetails> getNodesInfo() {
    return Collections.unmodifiableCollection(replicasInfos);
  }

  @Override
  public void close() throws IOException {
    logger.info("CLOSE NRT PRIMARY");
    Iterator<ReplicaDetails> it = replicasInfos.iterator();
    while (it.hasNext()) {
      ReplicaDetails replicaDetails = it.next();
      ReplicationServerClient replicationServerClient = replicaDetails.getReplicationServerClient();
      HostPort replicaHostPort = replicaDetails.getHostPort();
      logger.info(
          "CLOSE NRT PRIMARY, closing replica channel host:{}, port:{}",
          replicaHostPort.getHostName(),
          replicaHostPort.getPort());
      replicationServerClient.close();
      it.remove();
    }
    nrtDataManager.close();
    super.close();
  }
}
