#include "engine.h"
#include <jni.h>
#include <string>
#include <exception>
namespace { void fail(JNIEnv *e,const char *s) { e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),s); } }
extern "C" JNIEXPORT jlong JNICALL Java_me_zssu_ime_ime_Zenz32Native_load(JNIEnv *e,jobject,jstring path) {
 const char *p=e->GetStringUTFChars(path,nullptr); if (!p) return 0; std::string name(p); e->ReleaseStringUTFChars(path,p);
 try { return reinterpret_cast<jlong>(new Zenz32(name)); } catch (const std::exception &x) { fail(e,x.what()); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_me_zssu_ime_ime_Zenz32Native_generate(JNIEnv *e,jobject,jlong h,jbyteArray input,jlong ticket) {
 try { if (!h) return nullptr; std::string prompt(e->GetArrayLength(input),'\0'); e->GetByteArrayRegion(input,0,prompt.size(),reinterpret_cast<jbyte *>(prompt.data())); auto out=reinterpret_cast<Zenz32 *>(h)->generate(prompt,ticket); auto result=e->NewByteArray(out.size()); if (result) e->SetByteArrayRegion(result,0,out.size(),reinterpret_cast<const jbyte *>(out.data())); return result; } catch (const std::exception &x) { fail(e,x.what()); return nullptr; }
}
extern "C" JNIEXPORT jlong JNICALL Java_me_zssu_ime_ime_Zenz32Native_cancel(JNIEnv *,jobject) { return Zenz32::cancel(); }
extern "C" JNIEXPORT void JNICALL Java_me_zssu_ime_ime_Zenz32Native_free(JNIEnv *,jobject,jlong h) { delete reinterpret_cast<Zenz32 *>(h); }
