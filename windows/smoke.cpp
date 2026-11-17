#include <windows.h>
#include <msctf.h>
#include <cstdio>
#include "engine.h"

int main() {
    if (kana(L"nihongo",true)!=L"にほんご" ||
        kana(L"gakkou",true)!=L"がっこう" ||
        kana(L"kan",false)!=L"かn" ||
        kana(L"kan",true)!=L"かん" ||
        kana(L"kan'i",true)!=L"かんい" ||
        kana(L"kyouhaiitenki",true)!=L"きょうはいいてんき" ||
        candidate(L"はし",1)!=L"箸" ||
        candidate(L"かな",1)!=L"カナ" ||
        candidate(L"きょうはいいてんき",0)!=L"今日はいい天気") return 1;
    HMODULE dll=LoadLibraryW(L".\\KomorebiDesktop.dll");
    if (!dll) return 2;
    auto unload=reinterpret_cast<HRESULT(WINAPI*)()>(GetProcAddress(dll,"DllCanUnloadNow"));
    auto factory=reinterpret_cast<HRESULT(WINAPI*)(REFCLSID,REFIID,void**)>(GetProcAddress(dll,"DllGetClassObject"));
    if (!unload || !factory || !GetProcAddress(dll,"DllRegisterServer") ||
        !GetProcAddress(dll,"DllUnregisterServer") || unload()!=S_OK) return 3;
    const CLSID tip{0xc867f142,0x774a,0x4f03,{0xaf,0x9f,0x70,0xe6,0xb3,0xcc,0xa8,0x6a}};
    IClassFactory* f=nullptr;
    if (FAILED(factory(tip,IID_IClassFactory,reinterpret_cast<void**>(&f))) || unload()!=S_FALSE) return 4;
    ITfTextInputProcessor* svc=nullptr;
    if (FAILED(f->CreateInstance(nullptr,IID_ITfTextInputProcessor,reinterpret_cast<void**>(&svc)))) return 5;
    svc->Release(); f->Release();
    if (unload()!=S_OK) return 6;
    FreeLibrary(dll);
    std::puts("Komorebi Desktop: engine and COM factory passed");
}
