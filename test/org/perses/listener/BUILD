load("@io_bazel_rules_kotlin//kotlin:jvm.bzl", "kt_jvm_test")

kt_jvm_test(
    name = "StatisticsListenerTest",
    srcs = ["StatisticsListenerTest.kt"],
    associates = ["//src/org/perses/reduction/event"],
    data = ["golden_statistics_listener_test.properties"],
    deps = [
        "//:guava",
        "//:truth",
        "//src/org/perses/listener:listeners",
        "//src/org/perses/reduction:abstract_reducer",
        "//src/org/perses/reduction/reducer:node_based_reducer",
        "//src/org/perses/spartree",
    ],
)

kt_jvm_test(
    name = "TestScriptExecutionListenerTest",
    srcs = ["TestScriptExecutionListenerTest.kt"],
    deps = [
        "//:guava",
        "//:truth",
        "//src/org/perses/listener:listeners",
        "//src/org/perses/reduction:abstract_reducer",
    ],
)

kt_jvm_test(
    name = "NdjsonTraceListenerTest",
    srcs = ["NdjsonTraceListenerTest.kt"],
    associates = ["//src/org/perses/reduction/event"],
    data = ["//test_data/parentheses"],
    deps = [
        "//:guava",
        "//:truth",
        "//antlropt/src/org/perses/antlr:enum_rule_type",
        "//src/org/perses/listener:listeners",
        "//src/org/perses/program",
        "//src/org/perses/reduction:abstract_reducer",
        "//src/org/perses/reduction:property_test_result",
        "//src/org/perses/reduction:reducer_name_and_desc",
        "//src/org/perses/spartree",
        "//src/org/perses/util",
        "//src/org/perses/util:file_name_content_pair",
        "//src/org/perses/util/shell",
        "//test/org/perses:test_util",
    ],
)

kt_jvm_test(
    name = "NdjsonTraceListenerIntegrationTest",
    timeout = "long",
    srcs = ["NdjsonTraceListenerIntegrationTest.kt"],
    data = ["//test_data/ndjson_trace"],
    deps = [
        "//:guava",
        "//:truth",
        "//src/org/perses/reduction:abstract_reducer",
        "//src/org/perses/reduction:test_script_executor_service",
        "//src/org/perses/reduction/reducer:node_based_reducer",
        "//test/org/perses/reduction:reducer_functional_test_base",
    ],
)
