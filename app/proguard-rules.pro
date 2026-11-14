# Manifest-referenced components (services/receivers/workers) are kept automatically.
# WorkManager instantiates workers reflectively by class name.
-keep class cc.adamu.odosync.upload.** extends androidx.work.ListenableWorker { <init>(...); }
