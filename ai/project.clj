(defproject monolith-ai "0.1.0"
  :description "Netrunner AI research harness running the Jinteki.net engine fork in-process."
  :source-paths ["src" "../vendor/netrunner/src/clj" "../vendor/netrunner/src/cljc"]
  :resource-paths ["resources" "../sidecar/resources"]
  :jvm-opts ["-XX:+UseParallelGC" "-Xmx24g" "-Xss8m" "-XX:-OmitStackTraceInFastThrow" "-Djdk.attach.allowAttachSelf" "-XX:+UnlockDiagnosticVMOptions" "-XX:+DebugNonSafepoints"
             "-Dclojure.server.repl={:port 5555 :accept clojure.core.server/repl}"]
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [org.clojure/core.async "1.7.701"]
                 [com.taoensso/timbre "6.8.0"]
                 [com.taoensso/tufte "3.1.0"]
                 [cheshire/cheshire "5.10.1"]
                 [differ "0.3.3"]
                 [danlentz/clj-uuid "0.1.9"]
                 [potemkin "0.4.5"]
                 [com.noahbogart/cond-plus "1.4.0"]
                 [dev.weavejester/medley "1.8.0"]
                 [com.widdindustries/cljc.java-time "0.1.21"]
                 [time-literals "0.1.5"]
                 [org.flatland/ordered "1.15.12"]
                 [io.github.noahtheduke/fluent-clj "0.3.0"]
                 [org.slf4j/slf4j-nop "1.7.32"]
                 [metosin/malli "0.16.4"]
                 [com.clojure-goes-fast/clj-async-profiler "1.6.2"]])
