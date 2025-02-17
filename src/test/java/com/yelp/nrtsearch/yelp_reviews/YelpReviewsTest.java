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
package com.yelp.nrtsearch.yelp_reviews;

import com.google.gson.Gson;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.yelp.nrtsearch.server.config.NrtsearchConfig;
import com.yelp.nrtsearch.server.grpc.AddDocumentRequest;
import com.yelp.nrtsearch.server.grpc.CreateIndexRequest;
import com.yelp.nrtsearch.server.grpc.CreateIndexResponse;
import com.yelp.nrtsearch.server.grpc.FieldDefRequest;
import com.yelp.nrtsearch.server.grpc.FieldDefResponse;
import com.yelp.nrtsearch.server.grpc.GrpcServer;
import com.yelp.nrtsearch.server.grpc.HealthCheckRequest;
import com.yelp.nrtsearch.server.grpc.HealthCheckResponse;
import com.yelp.nrtsearch.server.grpc.LiveSettingsRequest;
import com.yelp.nrtsearch.server.grpc.LiveSettingsResponse;
import com.yelp.nrtsearch.server.grpc.Mode;
import com.yelp.nrtsearch.server.grpc.NrtsearchClient;
import com.yelp.nrtsearch.server.grpc.ReplicationServerClient;
import com.yelp.nrtsearch.server.grpc.SearchRequest;
import com.yelp.nrtsearch.server.grpc.SearchResponse;
import com.yelp.nrtsearch.server.grpc.SearcherVersion;
import com.yelp.nrtsearch.server.grpc.SettingsRequest;
import com.yelp.nrtsearch.server.grpc.SettingsResponse;
import com.yelp.nrtsearch.server.grpc.StartIndexRequest;
import com.yelp.nrtsearch.server.grpc.StartIndexResponse;
import com.yelp.nrtsearch.server.grpc.TransferStatusCode;
import com.yelp.nrtsearch.yelp_reviews.utils.OneDocBuilder;
import com.yelp.nrtsearch.yelp_reviews.utils.ParallelDocumentIndexer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.lucene.util.NamedThreadFactory;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

public class YelpReviewsTest {
  private static final Logger logger = LoggerFactory.getLogger(YelpReviewsTest.class.getName());
  public static final String NRTSEARCH_CONFIG_YAML = "nrtsearch_config.yaml";
  public static final String INDEX_NAME = "yelp_reviews_test_0";
  public static final String CLIENT_LOG = "client.log";
  public static final String SERVER_LOG = "server.log";

  enum ServerType {
    primary,
    replica,
    unknown
  }

  @CommandLine.Command(
      name = YelpReviewsTestCommand.YELP_REVIEWS,
      mixinStandardHelpOptions = true,
      version = "yelp_reviews 0.1",
      description = "Indexes Yelp reviews on a primary node and searches over them on a replica")
  public static class YelpReviewsTestCommand {
    public static final String YELP_REVIEWS = "yelp_reviews";
    public static final String defaultHost = "locahost";
    public static final String defaultPrimaryPorts = "6000,6001";
    public static final String defaultSecondaryPorts = "6002,6003";

    @CommandLine.Option(
        names = {"-ph", "--primary_host"},
        description = "host name of the primary node",
        required = false)
    private String primaryHost = defaultHost;

    public String getPrimaryHost() {
      return primaryHost;
    }

    @CommandLine.Option(
        names = {"-pp", "--primary_ports"},
        description =
            "comma separated primary ports, one each for app server and replication server",
        required = false)
    private String primaryPorts = defaultPrimaryPorts;

    public List<Integer> getPrimaryPorts() {
      return getPorts(primaryPorts);
    }

    @CommandLine.Option(
        names = {"-rh", "--replica_host"},
        description = "host name of the replica node",
        required = false)
    private String replicaHost = defaultHost;

    public String getReplicaHost() {
      return replicaHost;
    }

    @CommandLine.Option(
        names = {"-rp", "--replica_ports"},
        description =
            "comma separated replica ports, one each for app server and replication server",
        required = false)
    private String replicaPorts = defaultSecondaryPorts;

    public List<Integer> getReplicaPorts() {
      return getPorts(replicaPorts);
    }

    private List<Integer> getPorts(String ports) {
      return Arrays.stream(ports.split(","))
          .map(s -> Integer.parseInt(s))
          .collect(Collectors.toList());
    }
  }

  public static class YelpReview {
    private String review_id;
    private String user_id;
    private String business_id;
    private int stars;
    private int useful;
    private int funny;
    private int cool;
    private String text;
    private String date;

    public String getReview_id() {
      return review_id;
    }

    public void setReview_id(String review_id) {
      this.review_id = review_id;
    }

    public String getUser_id() {
      return user_id;
    }

    public void setUser_id(String user_id) {
      this.user_id = user_id;
    }

    public String getBusiness_id() {
      return business_id;
    }

    public void setBusiness_id(String business_id) {
      this.business_id = business_id;
    }

    public int getStars() {
      return stars;
    }

    public void setStars(int stars) {
      this.stars = stars;
    }

    public int getUseful() {
      return useful;
    }

    public void setUseful(int useful) {
      this.useful = useful;
    }

    public int getFunny() {
      return funny;
    }

    public void setFunny(int funny) {
      this.funny = funny;
    }

    public int getCool() {
      return cool;
    }

    public void setCool(int cool) {
      this.cool = cool;
    }

    public String getText() {
      return text;
    }

    public void setText(String text) {
      this.text = text;
    }

    public String getDate() {
      return date;
    }

    public void setDate(String date) {
      this.date = date;
    }
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    Path yelp_reviews_test_base_path =
        Paths.get(
            System.getProperty("user.home"), "lucene", "server", "scratch", "yelp_reviews_test");
    GrpcServer.rmDir(yelp_reviews_test_base_path);
    GrpcServer.rmDir(Paths.get("primary_state"));
    GrpcServer.rmDir(Paths.get("replica_state"));
    GrpcServer.rmDir(Paths.get("primary_index_base"));
    GrpcServer.rmDir(Paths.get("replica_index_base"));

    // create empty primary and secondary dirs
    Path primaryDir = yelp_reviews_test_base_path.resolve("primary");
    Path replicaDir = yelp_reviews_test_base_path.resolve("replica");
    Files.createDirectories(primaryDir);
    Files.createDirectories(replicaDir);

    // create primary and secondary, server and client log files
    String primaryClientCommandLog = primaryDir.resolve(CLIENT_LOG).toString();
    String secondaryClientCommandLog = replicaDir.resolve(CLIENT_LOG).toString();

    logger.info("Temporary directory: {}", yelp_reviews_test_base_path);
    Process primaryServerProcess =
        startServer(primaryDir.resolve(SERVER_LOG).toString(), getServerPrimaryConfigurationYaml());
    Process replicaServerProcess =
        startServer(replicaDir.resolve(SERVER_LOG).toString(), getServerReplicaConfigurationYaml());

    HostPort primaryHostPort = new HostPort(getServerPrimaryConfigurationYaml());
    HostPort secondaryHostPort = new HostPort(getServerReplicaConfigurationYaml());
    NrtsearchClient primaryServerClient =
        new NrtsearchClient(primaryHostPort.hostName, primaryHostPort.port);
    NrtsearchClient secondaryServerClient =
        new NrtsearchClient(secondaryHostPort.hostName, secondaryHostPort.port);

    // healthcheck, make sure servers are up
    ensureServersUp(primaryServerClient);
    ensureServersUp(secondaryServerClient);

    CompletableFuture<Process> primaryServer = primaryServerProcess.onExit();
    CompletableFuture<Process> replicaServer = replicaServerProcess.onExit();

    try {
      // create indexes
      createIndex(primaryServerClient);
      createIndex(secondaryServerClient);
      // live settings -- only primary
      liveSettings(primaryServerClient);
      // register
      registerFields(primaryServerClient);
      registerFields(secondaryServerClient);
      // settings
      settings(primaryServerClient, ServerType.primary);
      settings(secondaryServerClient, ServerType.replica);
      // start primary index
      StartIndexRequest startIndexRequest =
          StartIndexRequest.newBuilder()
              .setIndexName(INDEX_NAME)
              .setMode(Mode.PRIMARY)
              .setPrimaryGen(0)
              .build();
      startIndex(primaryServerClient, startIndexRequest);
      // start replica index
      startIndexRequest =
          StartIndexRequest.newBuilder()
              .setIndexName(INDEX_NAME)
              .setMode(Mode.REPLICA)
              .setPrimaryAddress(primaryHostPort.hostName)
              .setPort(primaryHostPort.replicationPort)
              .build();
      startIndex(secondaryServerClient, startIndexRequest);

      int availableProcessors = Runtime.getRuntime().availableProcessors();
      int MAX_INDEXING_THREADS =
          availableProcessors > 8 ? availableProcessors / 4 : availableProcessors;
      int MAX_SEARCH_THREADS =
          availableProcessors > 8 ? availableProcessors / 4 : availableProcessors;
      AtomicBoolean indexingDone = new AtomicBoolean(false);

      // check search hits on replica - in a separate threadpool
      final ExecutorService searchService =
          createExecutorService(MAX_SEARCH_THREADS, "LuceneSearch");
      Future<Double> searchFuture =
          searchService.submit(new SearchTask(secondaryServerClient, indexingDone));

      // index to primary - in a separate threadpool
      final ExecutorService indexService =
          createExecutorService(MAX_INDEXING_THREADS, "LuceneIndexing");
      Path reviews = Paths.get(System.getProperty("user.home"), "reviews.json");
      if (Files.exists(reviews)) {
        logger.info("Input file {} will be indexed", reviews);
      } else {
        String reviewStr = getPathAsStr("reviews.json", ServerType.unknown);
        logger.warn(
            "Input file {} does not exist using default resource from {}", reviews, reviewStr);
        reviews = Paths.get(reviewStr);
      }
      long t1 = System.nanoTime();
      List<Future<Long>> results =
          ParallelDocumentIndexer.buildAndIndexDocs(
              new OneDocBuilderImpl(), reviews, indexService, primaryServerClient);

      // wait till all indexing done and notify search thread once done
      for (Future<Long> each : results) {
        Long genId = each.get();
        logger.info("ParallelDocumentIndexer.buildAndIndexDocs returned genId: {}", genId);
      }
      long t2 = System.nanoTime();
      long timeMilliSecs = (t2 - t1) / (1000 * 1000);
      logger.info("ParallelDocumentIndexer.buildAndIndexDocs took {} milliSecs", timeMilliSecs);

      // stop search now
      logger.info("Signal SearchTask to end");
      indexingDone.set(true);
      logger.info("Search result totalHits: {}", searchFuture.get());

      // publishNRT, get latest searcher version and search over replica again with searcherVersion
      ReplicationServerClient primaryReplicationClient =
          new ReplicationServerClient(primaryHostPort.hostName, primaryHostPort.replicationPort);
      SearcherVersion searcherVersion = primaryReplicationClient.writeNRTPoint(INDEX_NAME);
      new SearchTask(secondaryServerClient, indexingDone)
          .getSearchTotalHits(searcherVersion.getVersion());
      logger.info("done...");

    } catch (StatusRuntimeException e) {
      logger.error("RPC failed with status {}", e.getStatus());
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      logger.error("Task launched async failed {}", e.getMessage());
      throw new RuntimeException(e);
    } finally {
      // stop servers
      primaryServer.cancel(true);
      replicaServer.cancel(true);
      primaryServerProcess.destroy();
      replicaServerProcess.destroy();
      logger.info("cleanup done...");
    }
  }

  public static void startIndex(NrtsearchClient serverClient, StartIndexRequest startIndexRequest) {
    StartIndexResponse startIndexResponse =
        serverClient.getBlockingStub().startIndex(startIndexRequest);
    logger.info(
        "numDocs: {}, maxDoc: {}, segments: {}, startTimeMS: {}",
        startIndexResponse.getNumDocs(),
        startIndexResponse.getMaxDoc(),
        startIndexResponse.getSegments(),
        startIndexResponse.getStartTimeMS());
  }

  private static void settings(NrtsearchClient serverClient, ServerType serverType)
      throws IOException {
    String settingsJson = readResourceAsString("settings.json", serverType);
    SettingsRequest settingsRequest = getSettings(settingsJson);
    SettingsResponse settingsResponse = serverClient.getBlockingStub().settings(settingsRequest);
    logger.info(settingsResponse.getResponse());
  }

  private static void registerFields(NrtsearchClient serverClient) throws IOException {
    String registerFieldsJson = readResourceAsString("register_fields.json", ServerType.unknown);
    FieldDefRequest fieldDefRequest = getFieldDefRequest(registerFieldsJson);
    FieldDefResponse fieldDefResponse =
        serverClient.getBlockingStub().registerFields(fieldDefRequest);
    logger.info(fieldDefResponse.getResponse());
  }

  private static void liveSettings(NrtsearchClient serverClient) {
    LiveSettingsRequest liveSettingsRequest =
        LiveSettingsRequest.newBuilder()
            .setIndexName(INDEX_NAME)
            .setIndexRamBufferSizeMB(256.0)
            .setMaxRefreshSec(1.0)
            .build();
    LiveSettingsResponse liveSettingsResponse =
        serverClient.getBlockingStub().liveSettings(liveSettingsRequest);
    logger.info(liveSettingsResponse.getResponse());
  }

  private static void createIndex(NrtsearchClient serverClient) {
    CreateIndexResponse response =
        serverClient
            .getBlockingStub()
            .createIndex(CreateIndexRequest.newBuilder().setIndexName(INDEX_NAME).build());
    logger.info(response.getResponse());
  }

  private static Process startServer(String logFilename, String configFileName) throws IOException {
    String command =
        String.format(
            "%s/build/install/nrtsearch/bin/nrtsearch_server %s",
            System.getProperty("user.dir"), configFileName);
    return issueCommand(logFilename, command);
  }

  private static Process issueCommand(String commandLog, String command) throws IOException {
    logger.info("issuing command: {}", command);
    ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command);
    File primaryLog = new File(commandLog);
    // merge error and output streams
    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(primaryLog);
    Process process = processBuilder.start();
    if (!process.isAlive() && process.exitValue() != 0) {
      String errorSt =
          String.format(
              "process: %s, exited with code: %s, command: %s, commandLog: %s",
              process.pid(), process.exitValue(), command, commandLog);
      logger.warn(errorSt);
      throw new RuntimeException(errorSt);
    }
    return process;
  }

  private static String getPathAsStr(String resourceName, ServerType serverType) {
    if (serverType.equals(ServerType.primary)) {
      return Paths.get("src", "test", "resources", "yelp_reviews", "primary", resourceName)
          .toAbsolutePath()
          .toString();
    } else if (serverType.equals(ServerType.replica)) {
      return Paths.get("src", "test", "resources", "yelp_reviews", "replica", resourceName)
          .toAbsolutePath()
          .toString();
    } else if (serverType.equals(ServerType.unknown)) {
      return Paths.get("src", "test", "resources", "yelp_reviews", resourceName)
          .toAbsolutePath()
          .toString();
    } else {
      throw new RuntimeException(String.format("Unknown ServerType passed: %s", serverType));
    }
  }

  private static String getServerPrimaryConfigurationYaml() {
    return getPathAsStr(NRTSEARCH_CONFIG_YAML, ServerType.primary);
  }

  private static String getServerReplicaConfigurationYaml() {
    return getPathAsStr(NRTSEARCH_CONFIG_YAML, ServerType.replica);
  }

  private static String readResourceAsString(String resourceName, ServerType serverType)
      throws IOException {
    String registerFields = getPathAsStr(resourceName, serverType);
    return Files.readString(Paths.get(registerFields));
  }

  private static class HostPort {
    private final String hostName;
    private final int port;
    private final int replicationPort;

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder("HostPort{");
      sb.append("hostName='").append(hostName).append('\'');
      sb.append(", port=").append(port);
      sb.append(", replicationPort=").append(replicationPort);
      sb.append('}');
      return sb.toString();
    }

    HostPort(String configFileName) throws FileNotFoundException {
      NrtsearchConfig configuration = new NrtsearchConfig(new FileInputStream(configFileName));
      this.hostName = configuration.getHostName();
      this.port = configuration.getPort();
      this.replicationPort = configuration.getReplicationPort();
    }
  }

  static FieldDefRequest getFieldDefRequest(String jsonStr) {
    logger.debug("Converting fields {} to proto FieldDefRequest", jsonStr);
    FieldDefRequest.Builder fieldDefRequestBuilder = FieldDefRequest.newBuilder();
    try {
      JsonFormat.parser().merge(jsonStr, fieldDefRequestBuilder);
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
    FieldDefRequest fieldDefRequest = fieldDefRequestBuilder.build();
    logger.debug("jsonStr converted to proto FieldDefRequest {}", fieldDefRequest);
    return fieldDefRequest;
  }

  private static SettingsRequest getSettings(String jsonStr) {
    logger.debug("Converting fields {} to proto SettingsRequest", jsonStr);
    SettingsRequest.Builder builder = SettingsRequest.newBuilder();
    try {
      JsonFormat.parser().merge(jsonStr, builder);
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
    SettingsRequest settingsRequest = builder.build();
    logger.debug("jsonStr converted to proto SettingsRequest {}", settingsRequest);
    return settingsRequest;
  }

  private static void ensureServersUp(NrtsearchClient serverClient) throws InterruptedException {
    int retry = 0;
    final int RETRY_LIMIT = 10;
    while (retry < RETRY_LIMIT) {
      try {
        HealthCheckResponse health =
            serverClient.getBlockingStub().status(HealthCheckRequest.newBuilder().build());
        if (health.getHealth().equals(TransferStatusCode.Done)) {
          return;
        } else {
          throw new StatusRuntimeException(Status.INTERNAL);
        }
      } catch (Exception e) {
        retry += 1;
        logger.warn("Servers not up yet...retry healthcheck {}/{} time", retry, RETRY_LIMIT);
        Thread.sleep(1000);
      }
    }
    if (retry >= RETRY_LIMIT) {
      throw new RuntimeException("Servers not up giving up..");
    }
  }

  private static class OneDocBuilderImpl implements OneDocBuilder {

    @Override
    public AddDocumentRequest buildOneDoc(String line, Gson gson) {
      AddDocumentRequest.Builder addDocumentRequestBuilder = AddDocumentRequest.newBuilder();
      addDocumentRequestBuilder.setIndexName(INDEX_NAME);
      YelpReview yelpReview = gson.fromJson(line, YelpReview.class);
      addField("review_id", yelpReview.getReview_id(), addDocumentRequestBuilder);
      addField("business_id", yelpReview.getBusiness_id(), addDocumentRequestBuilder);
      addField("user_id", yelpReview.getUser_id(), addDocumentRequestBuilder);
      addField("date", yelpReview.getDate(), addDocumentRequestBuilder);
      addField("text", yelpReview.getText(), addDocumentRequestBuilder);
      addField("funny", String.valueOf(yelpReview.getFunny()), addDocumentRequestBuilder);
      addField("cool", String.valueOf(yelpReview.getCool()), addDocumentRequestBuilder);
      addField("useful", String.valueOf(yelpReview.getUseful()), addDocumentRequestBuilder);
      addField("stars", String.valueOf(yelpReview.getStars()), addDocumentRequestBuilder);
      AddDocumentRequest addDocumentRequest = addDocumentRequestBuilder.build();
      return addDocumentRequest;
    }
  }

  private static class SearchTask implements Callable<Double> {

    private final NrtsearchClient nrtsearchClient;
    private final AtomicBoolean indexingDone;

    SearchTask(NrtsearchClient nrtsearchClient, AtomicBoolean indexingDone) {
      this.nrtsearchClient = nrtsearchClient;
      this.indexingDone = indexingDone;
    }

    /**
     * Computes a result, or throws an exception if unable to do so.
     *
     * @return computed result
     * @throws Exception if unable to compute a result
     */
    @Override
    public Double call() throws Exception {
      while (true) {
        if (indexingDone.get()) {
          logger.info("Indexing completed..");
          return getSearchTotalHits(0);
        } else {
          Thread.sleep(1000);
          getSearchTotalHits(0);
        }
      }
    }

    public double getSearchTotalHits(long searcherVersion) {
      List<String> RETRIEVED_VALUES =
          Arrays.asList(
              "review_id",
              "user_id",
              "business_id",
              "text",
              "date",
              "stars",
              "cool",
              "useful",
              "funny");
      SearchRequest.Builder searchRequestBuilder =
          SearchRequest.newBuilder()
              .setIndexName(INDEX_NAME)
              .setStartHit(0)
              .setTopHits(10)
              .setTotalHitsThreshold(Integer.MAX_VALUE)
              .addAllRetrieveFields(RETRIEVED_VALUES)
              .setQueryText("*:*");
      if (searcherVersion != 0) {
        searchRequestBuilder.setVersion(searcherVersion);
      }
      SearchRequest searchRequest = searchRequestBuilder.build();
      long t1 = System.nanoTime();
      SearchResponse searchResponse = this.nrtsearchClient.getBlockingStub().search(searchRequest);
      long timeMs = (System.nanoTime() - t1) / (1000 * 1000);
      long totalHits = searchResponse.getTotalHits().getValue();
      String threadId = Thread.currentThread().getName() + Thread.currentThread().threadId();
      logger.info(
          "Search returned totalHits: {} on threadId: {} in {} milliSecs",
          totalHits,
          threadId,
          timeMs);
      return totalHits;
    }
  }

  public static ExecutorService createExecutorService(int threadPoolSize, String threadNamePrefix) {
    final int MAX_BUFFERED_ITEMS = Math.max(100, 2 * threadPoolSize);
    // Seems to be substantially faster than ArrayBlockingQueue at high throughput:
    final BlockingQueue<Runnable> capacity = new LinkedBlockingQueue<Runnable>(MAX_BUFFERED_ITEMS);
    // same as Executors.newFixedThreadPool except we want a NamedThreadFactory instead of
    // defaultFactory
    return new ThreadPoolExecutor(
        threadPoolSize,
        threadPoolSize,
        0,
        TimeUnit.SECONDS,
        capacity,
        new NamedThreadFactory(threadNamePrefix));
  }

  @Test
  public void runYelpReviews() throws IOException, InterruptedException {
    YelpReviewsTest.main(null);
  }
}
