# Both native libraries export JNI symbols using these exact binary class and method names.
# R8 currently infers this from `native`, but the release contract is important enough to keep
# explicit: a renamed boundary builds successfully and then fails only when the IME first starts.
-keep class com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI {
    native <methods>;
}

-keep class me.zssu.ime.karukan.KarukanNative {
    native <methods>;
}

-keep class me.zssu.ime.ime.ZenzaiNative { *; }

-keep class me.zssu.ime.ime.ProofreadNative { *; }
