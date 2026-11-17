#pragma once
#include <string>
#include <string_view>
#include "tables.inc"

inline std::wstring kana(std::wstring_view in, bool final = false) {
    std::wstring out;
    auto vowel = [](wchar_t c) { return std::wstring_view(L"aeiou").find(c) != std::wstring_view::npos; };
    while (!in.empty()) {
        const auto c = in.front();
        if (c == L'n' && (in.size() == 1 ? final :
            in[1] == L'\'' || (!vowel(in[1]) && in[1] != L'y' && in[1] != L'n'))) {
            out += L'ん'; in.remove_prefix(in.size() >= 2 && in[1] == L'\'' ? 2 : 1); continue;
        }
        if (in.size() >= 2 && c == in[1] && c != L'n' && c >= L'a' && c <= L'z' && !vowel(c)) {
            out += L'っ'; in.remove_prefix(1); continue;
        }
        const Rule* best = nullptr;
        bool prefix = false;
        for (const auto& r : romaji) {
            if (in.starts_with(r.key) && (!best || r.key.size() > best->key.size())) best = &r;
            if (r.key.size() > in.size() && r.key.starts_with(in)) prefix = true;
        }
        if (prefix && !final) { out += in; break; }
        if (best) { out += best->value; in.remove_prefix(best->key.size()); }
        else { out += c; in.remove_prefix(1); }
    }
    return out;
}
inline std::wstring candidate(std::wstring_view input, size_t index) {
    for (const auto& r : dictionary)
        if (r.key == input && index-- == 0) return std::wstring(r.value);
    if (index == 0) return std::wstring(input);
    if (index == 1) {
        std::wstring out(input);
        for (auto& c : out) if (c >= L'ぁ' && c <= L'ゖ') c += 0x60;
        if (out != input) return out;
    }
    return {};
}
