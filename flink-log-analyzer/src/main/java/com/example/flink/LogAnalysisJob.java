package com.example.flink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.elasticsearch7.ElasticsearchSink;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.http.HttpHost;

import java.util.*;

/**
 * Flink 实时日志分析作业
 *
 * 数据流: Kafka(app-logs) → Flink解析 → Elasticsearch
 *
 * 功能:
 * 1. 解析 Java 日志（时间、级别、服务名、消息）
 * 2. 提取 ERROR/WARN 级别日志 → app-errors 索引
 * 3. 全量日志 → app-logs 索引
 * 4. 按服务统计日志量（30秒窗口）→ app-stats 索引
 */
public class LogAnalysisJob {

    public static void main(String[] args) throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(60000);
        env.setParallelism(1);

        // ==================== Kafka Source ====================
        Properties kafkaProps = new Properties();
        kafkaProps.setProperty("bootstrap.servers", args.length > 0 ? args[0] : "kafka:9092");
        kafkaProps.setProperty("group.id", "flink-log-analyzer");

        FlinkKafkaConsumer<String> kafkaConsumer = new FlinkKafkaConsumer<>(
                "app-logs",
                new SimpleStringSchema(),
                kafkaProps
        );
        kafkaConsumer.setStartFromLatest();

        DataStream<String> rawLogs = env.addSource(kafkaConsumer).name("Kafka Source");

        // ==================== 解析日志 ====================
        SingleOutputStreamOperator<ObjectNode> parsedLogs = rawLogs
                .map(new LogParser())
                .name("Log Parser");

        // ==================== ES 配置 ====================
        List<HttpHost> esHosts = Collections.singletonList(
                new HttpHost("elasticsearch", 9200, "http")
        );

        // ==================== 1. 全量日志 → ES ====================
        ElasticsearchSink.Builder<ObjectNode> esSinkAll = new ElasticsearchSink.Builder<>(
                esHosts,
                (element, ctx, indexer) -> {
                    indexer.add(
                            new org.elasticsearch.action.index.IndexRequest("app-logs")
                                    .source(element.toString(), org.elasticsearch.common.xcontent.XContentType.JSON)
                                    .id(element.has("log_id") ? element.get("log_id").asText() : null)
                    );
                }
        );
        esSinkAll.setBulkFlushMaxActions(50);
        esSinkAll.setBulkFlushInterval(5000L);
        parsedLogs.addSink(esSinkAll.build()).name("ES Sink - All Logs");

        // ==================== 2. ERROR/WARN → ES ====================
        SingleOutputStreamOperator<ObjectNode> errorLogs = parsedLogs
                .filter(node -> {
                    String level = node.has("level") ? node.get("level").asText() : "";
                    return "ERROR".equalsIgnoreCase(level) || "WARN".equalsIgnoreCase(level);
                })
                .name("Filter Errors");

        ElasticsearchSink.Builder<ObjectNode> esSinkErrors = new ElasticsearchSink.Builder<>(
                esHosts,
                (element, ctx, indexer) -> {
                    indexer.add(
                            new org.elasticsearch.action.index.IndexRequest("app-errors")
                                    .source(element.toString(), org.elasticsearch.common.xcontent.XContentType.JSON)
                    );
                }
        );
        esSinkErrors.setBulkFlushMaxActions(10);
        esSinkErrors.setBulkFlushInterval(2000L);
        errorLogs.addSink(esSinkErrors.build()).name("ES Sink - Errors");

        // ==================== 3. 按服务统计 → ES ====================
        parsedLogs
                .map(node -> {
                    String service = node.has("service") ? node.get("service").asText() : "unknown";
                    String level = node.has("level") ? node.get("level").asText() : "INFO";
                    return new Tuple2<>(service + ":" + level, 1L);
                })
                .returns(new org.apache.flink.api.common.typeinfo.TypeHint<Tuple2<String, Long>>() {})
                .keyBy(value -> value.f0)
                .timeWindow(org.apache.flink.streaming.api.windowing.time.Time.seconds(30))
                .reduce((v1, v2) -> new Tuple2<>(v1.f0, v1.f1 + v2.f1))
                .map(new StatsToESMapper())
                .addSink(new ElasticsearchSink.Builder<String>(
                        esHosts,
                        (element, ctx, indexer) -> {
                            try {
                                org.elasticsearch.common.xcontent.XContentType json =
                                        org.elasticsearch.common.xcontent.XContentType.JSON;
                                indexer.add(
                                        new org.elasticsearch.action.index.IndexRequest("app-stats")
                                                .source(element, json)
                                );
                            } catch (Exception ignored) {}
                        }
                ).build()).name("ES Sink - Stats");

        // ==================== 4. 全量日志 → 按天归档文件（日志审计） ====================
        // 每小时追加到当天的日志文件: /opt/flink-stack/archive/{service}/yyyy-MM-dd.log
        parsedLogs.addSink(new ArchiveSink()).name("Archive Sink - Daily Files");

        env.execute("AI Chat System - Log Analysis Job");
    }

    /**
     * 日志归档 Sink
     * 按服务名+日期分文件，每小时 flush 一次，同一天追加到同一文件
     * 文件路径: /opt/flink-stack/archive/{service}/yyyy-MM-dd.log
     */
    public static class ArchiveSink extends org.apache.flink.streaming.api.functions.sink.RichSinkFunction<ObjectNode> {
        private static final String ARCHIVE_DIR = "/opt/flink-stack/archive";
        private transient java.io.FileWriter writer;
        private transient String currentDate;
        private transient String currentService;
        private transient long lastFlushTime;

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) throws Exception {
            super.open(parameters);
            new java.io.File(ARCHIVE_DIR).mkdirs();
            lastFlushTime = System.currentTimeMillis();
        }

        @Override
        public void invoke(ObjectNode node, org.apache.flink.streaming.api.functions.sink.SinkFunction.Context context) throws Exception {
            String service = node.has("service") ? node.get("service").asText() : "unknown";
            String date = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());

            // 日期或服务变化时切换文件
            if (writer == null || !date.equals(currentDate) || !service.equals(currentService)) {
                closeWriter();
                java.io.File dir = new java.io.File(ARCHIVE_DIR + "/" + service);
                if (!dir.exists() && !dir.mkdirs()) {
                    // mkdirs 失败可能是权限问题，尝试用绝对路径
                    dir = new java.io.File("/tmp/archive/" + service);
                    dir.mkdirs();
                }
                java.io.File file = new java.io.File(dir, date + ".log");
                writer = new java.io.FileWriter(file, true); // 追加模式
                currentDate = date;
                currentService = service;
            }

            // 写入格式化日志行
            String timestamp = node.has("timestamp") ? node.get("timestamp").asText() :
                    node.has("@timestamp") ? node.get("@timestamp").asText() : "";
            String level = node.has("level") ? node.get("level").asText() : "INFO";
            String logger = node.has("logger") ? node.get("logger").asText() : "";
            String message = node.has("message") ? node.get("message").asText() : node.toString();

            StringBuilder sb = new StringBuilder();
            sb.append(timestamp).append(" ");
            sb.append(String.format("%-5s", level)).append(" ");
            sb.append("[").append(service).append("] ");
            if (!logger.isEmpty()) sb.append(logger).append(" - ");
            sb.append(message).append("\n");

            writer.write(sb.toString());

            // 每 10 秒 flush 一次（保证近实时写入）
            long now = System.currentTimeMillis();
            if (now - lastFlushTime > 10000) {
                writer.flush();
                lastFlushTime = now;
            }
        }

        @Override
        public void close() throws Exception {
            closeWriter();
        }

        private void closeWriter() {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (Exception ignored) {}
                writer = null;
            }
        }
    }

    /**
     * 日志解析器：解析 Filebeat JSON + Java 日志行
     */
    public static class LogParser implements MapFunction<String, ObjectNode> {
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public ObjectNode map(String raw) throws Exception {
            ObjectNode node = mapper.createObjectNode();

            try {
                ObjectNode fb = (ObjectNode) mapper.readTree(raw);

                if (fb.has("service")) {
                    node.put("service", fb.get("service").asText());
                } else {
                    node.put("service", "unknown");
                }
                if (fb.has("log_type")) {
                    node.put("log_type", fb.get("log_type").asText());
                }
                if (fb.has("hostname")) {
                    node.put("hostname", fb.get("hostname").asText());
                }

                String message = fb.has("message") ? fb.get("message").asText() : raw;
                node.put("raw_message", message);

                parseJavaLog(message, node);

            } catch (Exception e) {
                node.put("message", raw);
                node.put("service", "unknown");
                node.put("level", "INFO");
                node.put("raw_message", raw);
            }

            node.put("log_id", UUID.randomUUID().toString());
            node.put("@timestamp", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .format(new java.util.Date()));

            return node;
        }

        private void parseJavaLog(String message, ObjectNode node) {
            // 格式: 2024-01-01 12:00:00.123 INFO [thread] ClassName - message
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+)\\s+" +
                    "(\\w+)\\s+" +
                    "\\[([^\\]]+)\\]\\s+" +
                    "([\\w.]+)\\s+-\\s+" +
                    "(.*)$"
            );
            java.util.regex.Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                node.put("timestamp", matcher.group(1));
                node.put("level", matcher.group(2));
                node.put("thread", matcher.group(3));
                node.put("logger", matcher.group(4));
                node.put("message", matcher.group(5));
            } else {
                java.util.regex.Pattern simplePattern = java.util.regex.Pattern.compile(
                        "^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+)\\s+(\\w+)\\s+(.*)$"
                );
                java.util.regex.Matcher simpleMatcher = simplePattern.matcher(message);
                if (simpleMatcher.find()) {
                    node.put("timestamp", simpleMatcher.group(1));
                    node.put("level", simpleMatcher.group(2));
                    node.put("message", simpleMatcher.group(3));
                } else {
                    node.put("level", "INFO");
                    node.put("message", message);
                }
            }

            // 异常检测
            if (message.contains("Exception") || message.contains("Caused by")) {
                String level = node.has("level") ? node.get("level").asText() : "INFO";
                if ("INFO".equalsIgnoreCase(level)) {
                    node.put("level", "ERROR");
                }
            }
        }
    }

    /**
     * 统计结果转 JSON 字符串
     */
    public static class StatsToESMapper implements MapFunction<Tuple2<String, Long>, String> {
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String map(Tuple2<String, Long> value) throws Exception {
            ObjectNode node = mapper.createObjectNode();
            String[] parts = value.f0.split(":");
            node.put("service", parts.length > 0 ? parts[0] : "unknown");
            node.put("level", parts.length > 1 ? parts[1] : "INFO");
            node.put("count", value.f1);
            node.put("@timestamp", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .format(new java.util.Date()));
            node.put("window", "30s");
            return mapper.writeValueAsString(node);
        }
    }
}
