#include "engine.h"
#include "llama.h"
#include <algorithm>
#include <chrono>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>
namespace {
std::atomic<int64_t> serial{0};
std::once_flag init;
struct Abort {
 int64_t ticket;
 std::chrono::steady_clock::time_point deadline;
 static bool check(void *p) { auto &a = *static_cast<Abort *>(p); return serial.load() != a.ticket || std::chrono::steady_clock::now() >= a.deadline; }
};
}
int64_t Zenz32::cancel() { return ++serial; }
Zenz32::Zenz32(const std::string &path) {
 std::call_once(init, [] { llama_backend_init(); });
 auto mp = llama_model_default_params(); mp.n_gpu_layers = 0; mp.use_mmap = true;
 model = llama_model_load_from_file(path.c_str(), mp);
 if (!model) throw std::runtime_error("Could not load zenz model");
 auto cp = llama_context_default_params(); cp.n_ctx = 512; cp.n_batch = 512; cp.n_ubatch = 512;
 cp.n_threads = std::max(1u, std::min(4u, std::thread::hardware_concurrency())); cp.n_threads_batch = cp.n_threads;
 context = llama_init_from_model(model, cp);
 if (!context) { llama_model_free(model); model = nullptr; throw std::runtime_error("Could not create zenz context"); }
}
Zenz32::~Zenz32() { if (context) llama_free(context); if (model) llama_model_free(model); }
std::string Zenz32::generate(const std::string &prompt, int64_t generation) {
 Abort abort{generation, std::chrono::steady_clock::now() + std::chrono::seconds(10)};
 if (Abort::check(&abort)) return {};
 auto *vocab = llama_model_get_vocab(model);
 std::vector<llama_token> tokens(prompt.size() + 8);
 int n = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), false, false);
 if (n <= 0 || n + 192 >= 512) return {};
 llama_memory_clear(llama_get_memory(context), true);
 llama_set_abort_callback(context, Abort::check, &abort);
 struct Reset { llama_context *c; ~Reset() { llama_set_abort_callback(c, nullptr, nullptr); } } reset{context};
 if (llama_decode(context, llama_batch_get_one(tokens.data(), n)) != 0) return {};
 std::string output;
 for (int i=0; i<192; ++i) {
  if (Abort::check(&abort)) return {};
  const float *logits=llama_get_logits_ith(context, -1); if (!logits) return {};
  llama_token token=std::max_element(logits, logits + llama_vocab_n_tokens(vocab)) - logits;
  if (llama_vocab_is_eog(vocab, token)) return output;
  char piece[128]; int size=llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
  if (size <= 0 || size > static_cast<int>(sizeof(piece))) return {};
  output.append(piece,size);
  if (llama_decode(context,llama_batch_get_one(&token,1)) != 0) return {};
 }
 return {};
}
