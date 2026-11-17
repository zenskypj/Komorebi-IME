#pragma once
#include <string>
#include <string_view>
#include <algorithm>
#include <vector>
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
// Keep the five best distinct paths at each reading position.
inline std::vector<std::wstring> candidates(std::wstring_view input) {
    std::vector<std::wstring> result;
    if (input.empty() || input.size()>128) return result;
    auto push = [&](std::wstring_view value) {
        if (std::find(result.begin(),result.end(),value)==result.end()) result.emplace_back(value);
    };
    for (const auto& r : dictionary) if (r.key==input) push(r.value);
    struct Path { int cost; std::wstring text; };
    std::vector<std::vector<Path>> paths(input.size()+1);
    paths[0].push_back({0,L""});
    auto extend = [&](size_t from,size_t to,std::wstring_view surface,int cost) {
        auto& target=paths[to];
        for (const auto& path : paths[from]) {
            auto value=path.text+std::wstring(surface);
            const auto price=path.cost+cost;
            auto duplicate=std::find_if(target.begin(),target.end(),[&](const Path& item){return item.text==value;});
            if (duplicate!=target.end()) {
                if (duplicate->cost<=price) continue;
                target.erase(duplicate);
            }
            auto at=std::upper_bound(target.begin(),target.end(),price,[](int a,const Path& b){return a<b.cost;});
            if (at==target.end() && target.size()>=5) continue;
            target.insert(at,{price,std::move(value)});
            if (target.size()>5) target.pop_back();
        }
    };
    if (std::size(lexicon)>1) {
        for (size_t i=0;i<input.size();++i) {
            const auto literal=input.substr(i,1);
            const bool particle=std::wstring_view(L"はがをにへとでのも").find(literal)!=std::wstring_view::npos;
            extend(i,i+1,literal,particle ? 300 : 8200);
            for (const auto& r : dictionary) {
                if (r.key.size()>1 && input.substr(i).starts_with(r.key))
                    extend(i,i+r.key.size(),r.value,2200);
            }
            for (size_t n=1;n<=16 && i+n<=input.size();++n) {
                auto key=input.substr(i,n);
                auto row=std::lower_bound(std::begin(lexicon),std::end(lexicon),key,
                    [](const Lexeme& x,std::wstring_view k){return x.key<k;});
                for (int count=0; row!=std::end(lexicon) && row->key==key && count<16; ++row,++count)
                    extend(i,i+n,row->value,2500 + std::clamp(row->cost,1200,14000));
            }
        }
        for (const auto& path:paths.back()) if (path.text!=input) push(path.text);
    }
    push(input);
    auto katakana=std::wstring(input);
    for (auto& c : katakana) if (c>=L'ぁ' && c<=L'ゖ') c+=0x60;
    push(katakana);
    return result;
}
inline std::wstring candidate(std::wstring_view input,size_t index) {
    auto list=candidates(input);
    return index<list.size() ? std::move(list[index]) : std::wstring();
}
