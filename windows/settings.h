#pragma once
#include <windows.h>
inline constexpr wchar_t settingsKey[] = L"Software\\ZenSky Project\\Komorebi Desktop";
inline bool readSetting(const wchar_t* name, bool fallback) {
    DWORD value=0, size=sizeof(value);
    return RegGetValueW(HKEY_CURRENT_USER,settingsKey,name,RRF_RT_REG_DWORD,nullptr,&value,&size)==ERROR_SUCCESS
        ? value!=0 : fallback;
}
inline bool writeSetting(const wchar_t* name, bool enabled) {
    const DWORD value=enabled;
    return RegSetKeyValueW(HKEY_CURRENT_USER,settingsKey,name,REG_DWORD,&value,sizeof(value))==ERROR_SUCCESS;
}
