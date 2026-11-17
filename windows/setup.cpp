#include <windows.h>
#include <shlobj.h>
#include <string>
#include <cwchar>

static int report(const wchar_t* stage, HRESULT hr) {
    wchar_t message[512];
    std::swprintf(message, 512, L"%ls failed (0x%08lX).\n\nTake a screenshot of this error if you need help.",
                  stage, static_cast<unsigned long>(hr));
    MessageBoxW(nullptr,message,L"Komorebi Desktop Setup",MB_OK|MB_ICONERROR);
    return 1;
}

int WINAPI wWinMain(HINSTANCE,HINSTANCE,PWSTR command,int) {
    const bool remove = std::wstring(command)==L"/uninstall";
    wchar_t exe[32768];
    const auto n=GetModuleFileNameW(nullptr,exe,32768);
    if (!n || n>=32768) return report(L"Locate setup",E_FAIL);
    std::wstring source(exe,n);
    source.resize(source.find_last_of(L'\\')+1);
    PWSTR programFiles=nullptr;
    auto hr=SHGetKnownFolderPath(FOLDERID_ProgramFilesX64,0,nullptr,&programFiles);
    if (FAILED(hr)) return report(L"Locate Program Files",hr);
    std::wstring folder=std::wstring(programFiles)+L"\\Komorebi Desktop";
    CoTaskMemFree(programFiles);
    const auto target=folder+L"\\KomorebiDesktop.dll";
    const auto settings=folder+L"\\KomorebiSettings.exe";
    if (!remove) {
        if (!CreateDirectoryW(folder.c_str(),nullptr) && GetLastError()!=ERROR_ALREADY_EXISTS)
            return report(L"Create install directory",HRESULT_FROM_WIN32(GetLastError()));
        if (!CopyFileW((source+L"KomorebiDesktop.dll").c_str(),target.c_str(),FALSE))
            return report(L"Copy DLL (extract the entire ZIP and close apps using the old IME)",
                          HRESULT_FROM_WIN32(GetLastError()));
        if (!CopyFileW((source+L"KomorebiSettings.exe").c_str(),settings.c_str(),FALSE))
            return report(L"Copy settings application",HRESULT_FROM_WIN32(GetLastError()));
    } else if (GetFileAttributesW(target.c_str())==INVALID_FILE_ATTRIBUTES && GetLastError()==ERROR_FILE_NOT_FOUND) {
        MessageBoxW(nullptr,L"Komorebi Desktop is not installed.",L"Komorebi Desktop Setup",MB_OK);
        return 0;
    }
    auto dll=LoadLibraryExW(target.c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!dll) return report(L"Load DLL",HRESULT_FROM_WIN32(GetLastError()));
    auto proc=reinterpret_cast<HRESULT(WINAPI*)()>(GetProcAddress(dll,remove ? "DllUnregisterServer" : "DllRegisterServer"));
    hr=proc ? proc() : HRESULT_FROM_WIN32(GetLastError());
    FreeLibrary(dll);
    if (FAILED(hr)) return report(remove ? L"Unregister TSF input method" : L"Register and enable Japanese TSF profile",hr);
    if (remove && !DeleteFileW(target.c_str()))
        return report(L"Remove DLL (profile already unregistered; close applications)",HRESULT_FROM_WIN32(GetLastError()));
    PWSTR programs=nullptr;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_CommonPrograms,0,nullptr,&programs))) {
        auto shortcut=std::wstring(programs)+L"\\Komorebi Desktop.lnk";
        CoTaskMemFree(programs);
        if (remove) DeleteFileW(shortcut.c_str());
        else if (SUCCEEDED(CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED))) {
            IShellLinkW* link=nullptr;
            if (SUCCEEDED(CoCreateInstance(CLSID_ShellLink,nullptr,CLSCTX_INPROC_SERVER,
                    IID_IShellLinkW,reinterpret_cast<void**>(&link)))) {
                link->SetPath(settings.c_str());
                link->SetDescription(L"Komorebi Desktop Settings");
                IPersistFile* file=nullptr;
                if (SUCCEEDED(link->QueryInterface(IID_IPersistFile,reinterpret_cast<void**>(&file)))) {
                    file->Save(shortcut.c_str(),TRUE);
                    file->Release();
                }
                link->Release();
            }
            CoUninitialize();
        }
    }
    if (remove) DeleteFileW(settings.c_str());
    MessageBoxW(nullptr,remove ? L"Komorebi Desktop has been removed." :
        L"Komorebi Desktop was registered and enabled.\n\n"
        L"Press Win+Space and look under Japanese. If Japanese is not installed, add it in Windows language settings. "
        L"If the list is stale, sign out and sign in again. Open Komorebi Desktop from Start for settings.",
        L"Komorebi Desktop Setup",MB_OK|MB_ICONINFORMATION);
    return 0;
}
