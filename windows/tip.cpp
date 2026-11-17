#define INITGUID
#include <windows.h>
#include <msctf.h>
#include <inputscope.h>
#include <oleauto.h>
#include <atomic>
#include <new>
#include <utility>
#include "engine.h"
#include "settings.h"

static HMODULE module;
static std::atomic<long> objects{0}, locks{0};
static constexpr CLSID tip{0xc867f142,0x774a,0x4f03,{0xaf,0x9f,0x70,0xe6,0xb3,0xcc,0xa8,0x6a}};
static constexpr GUID profile{0x652fc916,0xdfc3,0x4ac7,{0xb8,0xcd,0x74,0xd2,0xcc,0xdc,0xf5,0x96}};
static constexpr LANGID ja = 0x0411;
static constexpr wchar_t name[] = L"Komorebi Desktop";

template<class T> struct Ptr {
    T* p{};
    Ptr() = default;
    Ptr(const Ptr&) = delete;
    Ptr& operator=(const Ptr&) = delete;
    ~Ptr() { reset(); }
    void reset() { if (auto old=std::exchange(p,nullptr)) old->Release(); }
    T** put() { reset(); return &p; }
    T* operator->() const { return p; }
    explicit operator bool() const { return p != nullptr; }
};

class Service;
class Edit final : public ITfEditSession {
    ULONG refs=1;
    Service* owner;
    ITfContext* context;
    WPARAM key;
public:
    Edit(Service*, ITfContext*, WPARAM);
    ~Edit();
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** out) override {
        if (!out) return E_POINTER;
        *out=nullptr;
        if (iid != IID_IUnknown && iid != IID_ITfEditSession) return E_NOINTERFACE;
        *out=static_cast<ITfEditSession*>(this); AddRef(); return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs; }
    ULONG STDMETHODCALLTYPE Release() override { auto n=--refs; if (!n) delete this; return n; }
    HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie) override;
};

class Service final : public ITfTextInputProcessor, public ITfKeyEventSink, public ITfCompositionSink {
    ULONG refs=1;
    Ptr<ITfThreadMgr> manager;
    Ptr<ITfContext> ownerContext;
    Ptr<ITfComposition> composition;
    TfClientId client=0;
    std::wstring raw;
    size_t choice=0;
    bool converting=false;
    bool enabled=true;
    HWND popup=nullptr;
    bool showCandidates=true;
    std::vector<std::wstring> list;
    static constexpr WPARAM toggleKey=0x10000;

    void hidePopup() {
        if (popup) DestroyWindow(std::exchange(popup,nullptr));
    }
    void showPopup(ITfContext* ctx,TfEditCookie ec,ITfRange* anchor,const std::wstring& text) {
        Ptr<ITfContextView> view;
        if (FAILED(ctx->GetActiveView(view.put()))) return;
        RECT rect{};
        BOOL clipped=FALSE;
        HWND owner=nullptr;
        if (FAILED(view->GetWnd(&owner)) || !owner || !IsWindow(owner) ||
            FAILED(view->GetTextExt(ec,anchor,&rect,&clipped)) || clipped) return;
        hidePopup();
        popup=CreateWindowExW(WS_EX_TOOLWINDOW|WS_EX_NOACTIVATE,L"STATIC",text.c_str(),
            WS_POPUP|WS_BORDER|SS_LEFT|SS_NOPREFIX,0,0,0,0,owner,nullptr,module,nullptr);
        if (!popup) return;
        SendMessageW(popup,WM_SETFONT,reinterpret_cast<WPARAM>(GetStockObject(DEFAULT_GUI_FONT)),0);
        RECT area{0,0,310,0};
        if (auto dc=GetDC(popup)) {
            auto font=SelectObject(dc,GetStockObject(DEFAULT_GUI_FONT));
            DrawTextW(dc,text.c_str(),-1,&area,DT_CALCRECT|DT_WORDBREAK|DT_NOPREFIX);
            SelectObject(dc,font); ReleaseDC(popup,dc);
        }
        MONITORINFO monitor{sizeof(MONITORINFO),{},{},0};
        int x=rect.left, y=rect.bottom, width=std::clamp<int>(area.right+16,48,330), height=area.bottom+12;
        if (GetMonitorInfoW(MonitorFromRect(&rect,MONITOR_DEFAULTTONEAREST),&monitor)) {
            x=std::clamp(x,static_cast<int>(monitor.rcWork.left),static_cast<int>(monitor.rcWork.right)-width);
            if (y+height>monitor.rcWork.bottom) y=rect.top-height;
        }
        SetWindowPos(popup,HWND_TOP,x,y,width,height,SWP_NOACTIVATE|SWP_SHOWWINDOW);
    }
    bool modeKey(WPARAM key) const {
        return key==VK_KANJI || key==VK_IME_ON || key==VK_IME_OFF ||
            (key=='J' && GetKeyState(VK_CONTROL)<0 && GetKeyState(VK_MENU)>=0 && GetKeyState(VK_SHIFT)>=0);
    }

    bool allowed(ITfContext* ctx) {
        if (!ctx) return false;
        Ptr<ITfCompartmentMgr> cm;
        if (SUCCEEDED(ctx->QueryInterface(IID_ITfCompartmentMgr, reinterpret_cast<void**>(cm.put())))) {
            for (auto id : {GUID_COMPARTMENT_KEYBOARD_DISABLED, GUID_COMPARTMENT_EMPTYCONTEXT}) {
                Ptr<ITfCompartment> compartment;
                if (SUCCEEDED(cm->GetCompartment(id,compartment.put()))) {
                    VARIANT value; VariantInit(&value);
                    auto hr=compartment->GetValue(&value);
                    bool blocked=SUCCEEDED(hr) && (value.vt==VT_I4 && value.lVal!=0);
                    VariantClear(&value);
                    if (blocked) return false;
                }
            }
        }
        return true;
    }

    bool sensitive(ITfContext* ctx, TfEditCookie ec) {
        Ptr<ITfReadOnlyProperty> prop;
        if (FAILED(ctx->GetAppProperty(GUID_PROP_INPUTSCOPE,prop.put()))) return false;
        TF_SELECTION sel{};
        ULONG fetched=0;
        if (FAILED(ctx->GetSelection(ec,TF_DEFAULT_SELECTION,1,&sel,&fetched)) || fetched!=1) return true;
        VARIANT value; VariantInit(&value);
        const auto hr=prop->GetValue(ec,sel.range,&value);
        sel.range->Release();
        Ptr<ITfInputScope> scopes;
        if (SUCCEEDED(hr) && value.vt==VT_UNKNOWN && value.punkVal)
            value.punkVal->QueryInterface(IID_ITfInputScope,reinterpret_cast<void**>(scopes.put()));
        VariantClear(&value);
        if (!scopes) return false;
        InputScope* values=nullptr;
        UINT count=0;
        if (FAILED(scopes->GetInputScopes(&values,&count))) return true;
        bool blocked=false;
        for (UINT i=0;i<count;++i) {
            const auto id=static_cast<int>(values[i]);
            // Password, private, numeric password/PIN and alphanumeric PIN.
            if (id==31 || id==61 || id==63 || id==64 || id==65 || id==66) blocked=true;
        }
        CoTaskMemFree(values);
        return blocked;
    }

    bool keySupported(ITfContext* ctx, WPARAM key) {
        if (!manager || !allowed(ctx) || (ownerContext && ctx!=ownerContext.p)) return false;
        if (modeKey(key)) return true;
        if (!enabled || GetKeyState(VK_CONTROL)<0 || GetKeyState(VK_MENU)<0 ||
            GetKeyState(VK_LWIN)<0 || GetKeyState(VK_RWIN)<0) return false;
        if (GetKeyState(VK_SHIFT)>=0 && key>='A' && key<='Z') return true;
        if (composition && GetKeyState(VK_SHIFT)>=0 &&
            (key==VK_OEM_7 || key==VK_OEM_COMMA || key==VK_OEM_PERIOD || key==VK_OEM_MINUS)) return true;
        return composition && (key==VK_SPACE || key==VK_RETURN || key==VK_ESCAPE || key==VK_BACK ||
            (converting && (key==VK_UP || key==VK_DOWN || (key>='1' && key<='9' && (choice/9)*9+key-'1'<list.size()))));
    }

    void clear() {
        hidePopup();
        raw.clear(); converting=false; choice=0; list.clear();
        composition.reset(); ownerContext.reset();
    }
public:
    Service() { ++objects; }
    ~Service() { hidePopup(); --objects; }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** out) override {
        if (!out) return E_POINTER;
        *out=nullptr;
        if (iid==IID_IUnknown || iid==IID_ITfTextInputProcessor) *out=static_cast<ITfTextInputProcessor*>(this);
        else if (iid==IID_ITfKeyEventSink) *out=static_cast<ITfKeyEventSink*>(this);
        else if (iid==IID_ITfCompositionSink) *out=static_cast<ITfCompositionSink*>(this);
        else return E_NOINTERFACE;
        AddRef(); return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs; }
    ULONG STDMETHODCALLTYPE Release() override { const auto n=--refs; if (!n) delete this; return n; }
    HRESULT STDMETHODCALLTYPE Activate(ITfThreadMgr* mgr,TfClientId id) override {
        if (!mgr || manager) return E_INVALIDARG;
        Ptr<ITfKeystrokeMgr> keys;
        auto hr=mgr->QueryInterface(IID_ITfKeystrokeMgr,reinterpret_cast<void**>(keys.put()));
        if (FAILED(hr)) return hr;
        enabled=readSetting(L"StartInKana",true);
        showCandidates=readSetting(L"ShowCandidates",true);
        hr=keys->AdviseKeyEventSink(id,this,TRUE);
        if (SUCCEEDED(hr)) { manager.p=mgr; mgr->AddRef(); client=id; }
        return hr;
    }
    HRESULT STDMETHODCALLTYPE Deactivate() override {
        if (manager) {
            if (ownerContext && composition) {
                BOOL eaten=FALSE;
                request(ownerContext.p,VK_RETURN,&eaten);
            }
            Ptr<ITfKeystrokeMgr> keys;
            if (SUCCEEDED(manager->QueryInterface(IID_ITfKeystrokeMgr,reinterpret_cast<void**>(keys.put()))))
                keys->UnadviseKeyEventSink(client);
        }
        clear(); manager.reset(); client=0; return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnSetFocus(BOOL foreground) override {
        if (!foreground) hidePopup();
        if (!foreground && ownerContext && composition) {
            BOOL eaten=FALSE;
            request(ownerContext.p,VK_RETURN,&eaten);
        }
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnTestKeyDown(ITfContext* ctx,WPARAM key,LPARAM,BOOL* eaten) override {
        if (!eaten) return E_POINTER;
        *eaten=keySupported(ctx,key);
        if (!*eaten && !composition) hidePopup();
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnTestKeyUp(ITfContext*,WPARAM,LPARAM,BOOL* eaten) override {
        if (!eaten) return E_POINTER;
        *eaten=FALSE; return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnKeyUp(ITfContext* ctx,WPARAM k,LPARAM l,BOOL* eaten) override { return OnTestKeyUp(ctx,k,l,eaten); }
    HRESULT STDMETHODCALLTYPE OnPreservedKey(ITfContext*,REFGUID,BOOL* eaten) override {
        if (!eaten) return E_POINTER;
        *eaten=FALSE; return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnKeyDown(ITfContext* ctx,WPARAM key,LPARAM,BOOL* eaten) override {
        if (!eaten) return E_POINTER;
        *eaten=FALSE;
        if (!keySupported(ctx,key)) return S_OK;
        if (modeKey(key)) {
            if (composition) {
                BOOL committed=FALSE;
                request(ctx,VK_RETURN,&committed);
                if (!committed) return S_OK;
            }
            return request(ctx,key==VK_IME_ON ? toggleKey+1 : key==VK_IME_OFF ? toggleKey+2 : toggleKey,eaten);
        }
        return request(ctx,key,eaten);
    }
    HRESULT request(ITfContext* ctx, WPARAM key, BOOL* eaten) {
        auto session=new(std::nothrow) Edit(this,ctx,key);
        if (!session) return E_OUTOFMEMORY;
        HRESULT result=E_FAIL;
        auto hr=ctx->RequestEditSession(client,session,TF_ES_SYNC|TF_ES_READWRITE,&result);
        session->Release();
        *eaten=SUCCEEDED(hr) && result==S_OK;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnCompositionTerminated(TfEditCookie,ITfComposition* ended) override {
        if (composition.p==ended) clear();
        return S_OK;
    }
    HRESULT edit(ITfContext* ctx,WPARAM key,TfEditCookie ec) {
        if (key!=VK_RETURN && key!=VK_ESCAPE && sensitive(ctx,ec)) return S_FALSE;
        if (key>=toggleKey) {
            enabled=key==toggleKey ? !enabled : key==toggleKey+1;
            TF_SELECTION selection{};
            ULONG fetched=0;
            if (SUCCEEDED(ctx->GetSelection(ec,TF_DEFAULT_SELECTION,1,&selection,&fetched)) && fetched==1) {
                showPopup(ctx,ec,selection.range,enabled ? L"あ" : L"A");
                selection.range->Release();
            }
            return S_OK;
        }
        hidePopup();
        if (composition && key!=VK_RETURN && key!=VK_ESCAPE) {
            TF_SELECTION current{};
            ULONG fetched=0;
            Ptr<ITfRange> span;
            if (FAILED(ctx->GetSelection(ec,TF_DEFAULT_SELECTION,1,&current,&fetched)) || fetched!=1) return S_FALSE;
            Ptr<ITfRange> selection;
            selection.p=current.range;
            LONG start=1,end=1;
            if (FAILED(composition->GetRange(span.put())) ||
                FAILED(selection->CompareStart(ec,span.p,TF_ANCHOR_END,&start)) ||
                FAILED(selection->CompareEnd(ec,span.p,TF_ANCHOR_END,&end)) || start || end) {
                composition->EndComposition(ec);
                clear();
                return S_FALSE;
            }
        }
        auto next=raw;
        auto index=choice;
        bool convert=converting;
        const bool direct=convert && key>='1' && key<='9';
        bool commit=key==VK_RETURN || key==VK_ESCAPE || direct;
        if (key==VK_BACK) {
            if (convert) { convert=false; index=0; }
            else if (!next.empty()) next.pop_back();
        } else if (key==VK_SPACE || key==VK_DOWN) {
            index=convert ? index+1 : 0; convert=true;
        } else if (key==VK_UP && !list.empty()) {
            index=(index+list.size()-1)%list.size();
        } else if (direct) {
            index=(choice/9)*9+key-'1';
        } else if (key>='A' && key<='Z') {
            if (next.size()>=128) return S_FALSE;
            next+=static_cast<wchar_t>(key-'A'+L'a'); convert=false; index=0;
        }
        else if (key==VK_OEM_7 || key==VK_OEM_COMMA || key==VK_OEM_PERIOD || key==VK_OEM_MINUS) {
            if (next.size()>=128) return S_FALSE;
            next += key==VK_OEM_7 ? L'\'' : key==VK_OEM_COMMA ? L',' : key==VK_OEM_PERIOD ? L'.' : L'-';
            convert=false; index=0;
        }
        auto reading=kana(next,commit||convert);
        if (convert && (list.empty() || next!=raw)) list=candidates(reading);
        else if (!convert) list.clear();
        if (index>=list.size()) index=0;
        auto shown=convert && !list.empty() ? list[index] : reading;
        if (key==VK_ESCAPE) shown.clear();

        if (!composition) {
            Ptr<ITfInsertAtSelection> insertion;
            auto hr=ctx->QueryInterface(IID_ITfInsertAtSelection,reinterpret_cast<void**>(insertion.put()));
            if (FAILED(hr)) return hr;
            Ptr<ITfRange> initial;
            hr=insertion->InsertTextAtSelection(ec,TF_IAS_QUERYONLY,nullptr,0,initial.put());
            if (FAILED(hr)) return hr;
            Ptr<ITfContextComposition> container;
            hr=ctx->QueryInterface(IID_ITfContextComposition,reinterpret_cast<void**>(container.put()));
            if (FAILED(hr)) return hr;
            hr=container->StartComposition(ec,initial.p,this,composition.put());
            if (FAILED(hr) || !composition) return FAILED(hr) ? hr : E_FAIL;
            ownerContext.p=ctx; ctx->AddRef();
        }
        Ptr<ITfRange> range;
        auto hr=composition->GetRange(range.put());
        if (SUCCEEDED(hr)) hr=range->SetText(ec,0,shown.data(),static_cast<LONG>(shown.size()));
        if (FAILED(hr)) {
            if (raw.empty()) { composition->EndComposition(ec); clear(); }
            return hr;
        }
        raw=std::move(next); choice=index; converting=convert;
        if (SUCCEEDED(range->Collapse(ec,TF_ANCHOR_END))) {
            TF_SELECTION sel{range.p,{TF_AE_NONE,FALSE}};
            ctx->SetSelection(ec,1,&sel);
        }
        if (!commit && convert && showCandidates && !list.empty()) {
            std::wstring rows;
            const auto first=(index/9)*9;
            for (size_t i=first;i<list.size() && i<first+9;++i) {
                if (i>first) rows+=L"\r\n";
                rows+=(i==index ? L"▶ " : L"   ");
                rows+=std::to_wstring(i-first+1)+L"  "+list[i].substr(0,36);
            }
            showPopup(ctx,ec,range.p,rows);
        }
        if (commit || shown.empty()) {
            composition->EndComposition(ec);
            clear();
        }
        return S_OK;
    }
};

Edit::Edit(Service* svc,ITfContext* ctx,WPARAM k):owner(svc),context(ctx),key(k) {
    owner->AddRef(); context->AddRef(); ++objects;
}
Edit::~Edit() { context->Release(); owner->Release(); --objects; }
HRESULT Edit::DoEditSession(TfEditCookie ec) {
    try { return owner->edit(context,key,ec); } catch (...) { return E_FAIL; }
}

class Factory final : public IClassFactory {
    std::atomic<ULONG> refs{1};
public:
    Factory() { ++objects; }
    ~Factory() { --objects; }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid,void** out) override {
        if (!out) return E_POINTER;
        *out=nullptr;
        if (iid!=IID_IUnknown && iid!=IID_IClassFactory) return E_NOINTERFACE;
        *out=static_cast<IClassFactory*>(this); AddRef(); return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs; }
    ULONG STDMETHODCALLTYPE Release() override { const auto n=--refs; if (!n) delete this; return n; }
    HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown* outer,REFIID iid,void** out) override {
        if (!out) return E_POINTER;
        *out=nullptr;
        if (outer) return CLASS_E_NOAGGREGATION;
        auto svc=new(std::nothrow) Service;
        if (!svc) return E_OUTOFMEMORY;
        const auto hr=svc->QueryInterface(iid,out); svc->Release(); return hr;
    }
    HRESULT STDMETHODCALLTYPE LockServer(BOOL lock) override { if (lock) ++locks; else --locks; return S_OK; }
};

extern "C" BOOL WINAPI DllMain(HINSTANCE instance,DWORD reason,LPVOID) {
    if (reason==DLL_PROCESS_ATTACH) { module=instance; DisableThreadLibraryCalls(instance); }
    return TRUE;
}
extern "C" HRESULT WINAPI DllCanUnloadNow() { return objects==0 && locks==0 ? S_OK : S_FALSE; }
extern "C" HRESULT WINAPI DllGetClassObject(REFCLSID cls,REFIID iid,void** out) {
    if (!out) return E_POINTER;
    *out=nullptr;
    if (cls!=tip) return CLASS_E_CLASSNOTAVAILABLE;
    auto factory=new(std::nothrow) Factory;
    if (!factory) return E_OUTOFMEMORY;
    auto hr=factory->QueryInterface(iid,out); factory->Release(); return hr;
}

static HRESULT registerTsf(bool add,const wchar_t* path) {
    Ptr<ITfInputProcessorProfiles> profiles;
    Ptr<ITfCategoryMgr> categories;
    auto hr=CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,
        IID_ITfInputProcessorProfiles,reinterpret_cast<void**>(profiles.put()));
    if (FAILED(hr)) return hr;
    hr=CoCreateInstance(CLSID_TF_CategoryMgr,nullptr,CLSCTX_INPROC_SERVER,
        IID_ITfCategoryMgr,reinterpret_cast<void**>(categories.put()));
    if (FAILED(hr)) return hr;
    if (!add) {
        categories->UnregisterCategory(tip,GUID_TFCAT_TIP_KEYBOARD,tip);
        profiles->RemoveLanguageProfile(tip,ja,profile);
        return profiles->Unregister(tip);
    }
    hr=profiles->Register(tip);
    if (SUCCEEDED(hr)) hr=profiles->AddLanguageProfile(tip,ja,profile,name,ARRAYSIZE(name)-1,
        path,static_cast<ULONG>(wcslen(path)),0);
    if (SUCCEEDED(hr)) hr=categories->RegisterCategory(tip,GUID_TFCAT_TIP_KEYBOARD,tip);
    if (SUCCEEDED(hr)) hr=profiles->EnableLanguageProfile(tip,ja,profile,TRUE);
    if (FAILED(hr)) {
        categories->UnregisterCategory(tip,GUID_TFCAT_TIP_KEYBOARD,tip);
        profiles->RemoveLanguageProfile(tip,ja,profile);
        profiles->Unregister(tip);
    }
    return hr;
}

static HRESULT registration(bool add) {
    wchar_t dll[32768],guid[40];
    const DWORD size=GetModuleFileNameW(module,dll,ARRAYSIZE(dll));
    if (!size || size>=ARRAYSIZE(dll)) return E_FAIL;
    if (!StringFromGUID2(tip,guid,ARRAYSIZE(guid))) return E_FAIL;
    auto key=std::wstring(L"Software\\Classes\\CLSID\\")+guid;
    auto hr=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    if (FAILED(hr)) return hr;
    if (!add) {
        const auto result=registerTsf(false,dll);
        const auto removed=RegDeleteTreeW(HKEY_LOCAL_MACHINE,key.c_str());
        CoUninitialize();
        if (removed!=ERROR_SUCCESS && removed!=ERROR_FILE_NOT_FOUND) return HRESULT_FROM_WIN32(removed);
        return result;
    }
    HKEY handle=nullptr;
    const auto code=RegCreateKeyExW(HKEY_LOCAL_MACHINE,(key+L"\\InprocServer32").c_str(),0,nullptr,0,
        KEY_WRITE,nullptr,&handle,nullptr);
    if (code!=ERROR_SUCCESS) { CoUninitialize(); return HRESULT_FROM_WIN32(code); }
    auto error=RegSetValueExW(handle,nullptr,0,REG_SZ,reinterpret_cast<const BYTE*>(dll),(size+1)*sizeof(wchar_t));
    constexpr wchar_t apartment[]=L"Apartment";
    if (error==ERROR_SUCCESS) error=RegSetValueExW(handle,L"ThreadingModel",0,REG_SZ,
        reinterpret_cast<const BYTE*>(apartment),sizeof(apartment));
    RegCloseKey(handle);
    hr=error==ERROR_SUCCESS ? registerTsf(true,dll) : HRESULT_FROM_WIN32(error);
    if (FAILED(hr)) RegDeleteTreeW(HKEY_LOCAL_MACHINE,key.c_str());
    CoUninitialize(); return hr;
}
extern "C" HRESULT WINAPI DllRegisterServer() { try { return registration(true); } catch (...) { return E_FAIL; } }
extern "C" HRESULT WINAPI DllUnregisterServer() { try { return registration(false); } catch (...) { return E_FAIL; } }
