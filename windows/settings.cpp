#include <windows.h>
#include "settings.h"

namespace {
constexpr int kana=101, candidates=102, save=103;
HWND add(HWND owner,const wchar_t* type,const wchar_t* text,DWORD style,int x,int y,int w,int h,int id=0) {
    auto item=CreateWindowExW(0,type,text,WS_CHILD|WS_VISIBLE|style,x,y,w,h,owner,
        reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)),GetModuleHandleW(nullptr),nullptr);
    if (item) SendMessageW(item,WM_SETFONT,reinterpret_cast<WPARAM>(GetStockObject(DEFAULT_GUI_FONT)),TRUE);
    return item;
}
LRESULT CALLBACK windowProc(HWND window,UINT msg,WPARAM wp,LPARAM lp) {
    switch(msg) {
    case WM_CREATE:
        add(window,L"STATIC",L"Komorebi Desktop の入力設定",0,20,15,345,24);
        add(window,L"BUTTON",L"開始時は かな入力（あ）",BS_AUTOCHECKBOX|WS_TABSTOP,20,54,335,26,kana);
        add(window,L"BUTTON",L"変換候補をカーソルの近くに表示",BS_AUTOCHECKBOX|WS_TABSTOP,20,87,335,26,candidates);
        add(window,L"STATIC",L"Ctrl+J または 半角／全角：A ⇔ あ\r\n空白・上下：候補選択　 Enter：確定",0,20,125,350,52);
        add(window,L"BUTTON",L"保存",BS_DEFPUSHBUTTON|WS_TABSTOP,248,182,105,32,save);
        CheckDlgButton(window,kana,readSetting(L"StartInKana",true)?BST_CHECKED:BST_UNCHECKED);
        CheckDlgButton(window,candidates,readSetting(L"ShowCandidates",true)?BST_CHECKED:BST_UNCHECKED);
        return 0;
    case WM_COMMAND:
        if (LOWORD(wp)==save) {
            const bool ok=writeSetting(L"StartInKana",IsDlgButtonChecked(window,kana)==BST_CHECKED) &&
                writeSetting(L"ShowCandidates",IsDlgButtonChecked(window,candidates)==BST_CHECKED);
            MessageBoxW(window,ok ? L"保存しました。IME を切り替え直すと反映されます。" : L"設定を保存できませんでした。",
                L"Komorebi Desktop",MB_OK|(ok?MB_ICONINFORMATION:MB_ICONERROR));
            return 0;
        }
        break;
    case WM_DESTROY: PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(window,msg,wp,lp);
}
}
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE,PWSTR,int show) {
    WNDCLASSW cls{};
    cls.lpfnWndProc=windowProc; cls.hInstance=instance; cls.lpszClassName=L"KomorebiDesktopSettings";
    cls.hCursor=LoadCursorW(nullptr,IDC_ARROW);
    cls.hbrBackground=reinterpret_cast<HBRUSH>(COLOR_WINDOW+1);
    if (!RegisterClassW(&cls)) return 1;
    auto window=CreateWindowW(cls.lpszClassName,L"Komorebi Desktop 設定",WS_OVERLAPPED|WS_SYSMENU|WS_MINIMIZEBOX,
        CW_USEDEFAULT,CW_USEDEFAULT,396,271,nullptr,nullptr,instance,nullptr);
    if (!window) return 1;
    ShowWindow(window,show);
    MSG msg{};
    while(GetMessageW(&msg,nullptr,0,0)>0) {
        if (!IsDialogMessageW(window,&msg)) { TranslateMessage(&msg); DispatchMessageW(&msg); }
    }
    return static_cast<int>(msg.wParam);
}
