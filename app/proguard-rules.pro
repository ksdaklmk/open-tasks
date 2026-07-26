-keepattributes *Annotation*

# Room and Hilt publish their own consumer rules. Keep Tink key proto names used
# by serialized local keysets.
-keep class com.google.crypto.tink.proto.** { *; }

# Never emit task or vault content through release logging; lint enforces call
# sites and R8 removes low-priority Android log calls.
-maximumremovedandroidloglevel INFO
