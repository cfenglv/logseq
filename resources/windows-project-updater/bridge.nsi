Unicode true
!include "${BRIDGE_CONFIG}"
!include LogicLib.nsh
!include StdUtils.nsh

Name "Logseq"
RequestExecutionLevel user
SilentInstall silent
CRCCheck force
VIProductVersion "2.0.1.7"
VIAddVersionKey /LANG=1033 "ProductName" "Logseq"
VIAddVersionKey /LANG=1033 "ProductVersion" "2.0.1-selfhost.7"

LoadLanguageFile "${NSISDIR}\Contrib\Language files\English.nlf"
LoadLanguageFile "${NSISDIR}\Contrib\Language files\SimpChinese.nlf"

Var BridgeParameters
Var BridgeExit

Section
  InitPluginsDir
  SetOutPath "$PLUGINSDIR"
  nsisunz::Unzip "$EXEPATH" "$PLUGINSDIR"
  Pop $0
  StrCmp $0 "success" 0 bridge_error

  ClearErrors
  FileOpen $0 "$PLUGINSDIR\${BRIDGE_PAYLOAD_NAME}" r
  IfErrors bridge_error
  FileSeek $0 0 END $1
  FileClose $0
  IntCmp $1 ${BRIDGE_PAYLOAD_SIZE} 0 bridge_error bridge_error
  ${StdUtils.HashFile} $0 "SHA2-512" "$PLUGINSDIR\${BRIDGE_PAYLOAD_NAME}"
  StrCmp $0 "${BRIDGE_PAYLOAD_SHA512}" 0 bridge_error

  ${StdUtils.GetAllParameters} $BridgeParameters "0"
  ClearErrors
  ExecWait '"$PLUGINSDIR\${BRIDGE_PAYLOAD_NAME}" /S $BridgeParameters' $BridgeExit
  IfErrors bridge_error
  SetErrorLevel $BridgeExit
  Quit

bridge_error:
  StrCpy $LANGUAGE 1033
  System::Call 'kernel32::GetUserDefaultUILanguage()i.r0'
  ${If} $0 == 2052
    StrCpy $LANGUAGE 2052
  ${EndIf}
  MessageBox MB_OK|MB_ICONSTOP "$(BridgeErrorDescription)" /SD IDOK
  SetErrorLevel 57
  Quit
SectionEnd
