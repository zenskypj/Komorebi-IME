#pragma once
#include <atomic>
#include <memory>
#include <string>
struct llama_model;
struct llama_context;
class Zenz32 {
public:
 explicit Zenz32(const std::string &path);
 ~Zenz32();
 std::string generate(const std::string &prompt, int64_t generation);
 static int64_t cancel();
private:
 llama_model *model = nullptr;
 llama_context *context = nullptr;
};
