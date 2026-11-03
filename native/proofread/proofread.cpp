#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>
#include <memory>
#include <exception>
#include <string>
#include <thread>
#include <vector>
static std::atomic<int64_t> serial{0};
static std::once_flag init;
struct Run { int64_t id; std::chrono::steady_clock::time_point deadline; };
static bool abort_run(void *p) { auto &r=*static_cast<Run*>(p); return serial.load()!=r.id || std::chrono::steady_clock::now()>r.deadline; }
static void fail(JNIEnv *e,const char *s) { e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),s); }
extern "C" JNIEXPORT jlong JNICALL Java_me_zssu_ime_ime_ProofreadNative_cancel(JNIEnv*,jobject) { return ++serial; }
extern "C" JNIEXPORT jbyteArray JNICALL Java_me_zssu_ime_ime_ProofreadNative_generate(JNIEnv *e,jobject,jstring path,jbyteArray prompt,jlong ticket) {
 try {
 const char *c=e->GetStringUTFChars(path,nullptr); if(!c)return nullptr; std::string file(c); e->ReleaseStringUTFChars(path,c);
 std::string text(e->GetArrayLength(prompt),'\0'); e->GetByteArrayRegion(prompt,0,text.size(),reinterpret_cast<jbyte*>(text.data()));
 std::call_once(init,[]{llama_backend_init();}); Run run{ticket,std::chrono::steady_clock::now()+std::chrono::seconds(300)};
 if(abort_run(&run)) return nullptr;
 auto mp=llama_model_default_params();mp.n_gpu_layers=0;auto *model=llama_model_load_from_file(file.c_str(),mp);
 if(!model){fail(e,"モデルを読み込めませんでした");return nullptr;}
 std::unique_ptr<llama_model, decltype(&llama_model_free)> model_guard(model, llama_model_free);
 auto cp=llama_context_default_params();cp.n_ctx=2048;cp.n_batch=256;cp.n_ubatch=256;cp.n_threads=std::max(1u,std::min(4u,std::thread::hardware_concurrency()));cp.n_threads_batch=cp.n_threads;
 auto *ctx=llama_init_from_model(model,cp);if(!ctx){fail(e,"推論用メモリが不足しています");return nullptr;}
 std::unique_ptr<llama_context, decltype(&llama_free)> context_guard(ctx, llama_free);
 llama_set_abort_callback(ctx,abort_run,&run);const auto *vocab=llama_model_get_vocab(model);std::vector<llama_token> toks(text.size()+64);
 int n=llama_tokenize(vocab,text.data(),text.size(),toks.data(),toks.size(),false,true);std::string out;bool finished=false;
 if(n>0 && n<1500){toks.resize(n);bool ok=true;for(int pos=0;pos<n;pos+=256){int len=std::min(256,n-pos);if(llama_decode(ctx,llama_batch_get_one(toks.data()+pos,len))!=0){ok=false;break;}}
 for(int i=0;ok && i<std::min(1024, 2047-n) && !abort_run(&run);++i){const float *logits=llama_get_logits_ith(ctx,-1);if(!logits)break;int count=llama_vocab_n_tokens(vocab);llama_token tok=std::max_element(logits,logits+count)-logits;if(llama_vocab_is_eog(vocab,tok)){finished=true;break;}char b[256];int z=llama_token_to_piece(vocab,tok,b,sizeof(b),0,false);if(z<0 || z>256)break;out.append(b,z);if(llama_decode(ctx,llama_batch_get_one(&tok,1))!=0)break;}}
 if(!finished || abort_run(&run)){if(!abort_run(&run))fail(e,"校正結果を最後まで生成できませんでした");return nullptr;}
 auto result=e->NewByteArray(out.size());if(result)e->SetByteArrayRegion(result,0,out.size(),reinterpret_cast<const jbyte*>(out.data()));return result;
 } catch (const std::exception &) { fail(e, "校正処理を実行できませんでした。空きメモリを確認してください"); return nullptr; }
 catch (...) { fail(e, "校正処理を実行できませんでした"); return nullptr; }
}
