; Let phones on the Wi-Fi reach the receiver without a firewall prompt.
!macro customInstall
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Driftcast Receiver"'
  nsExec::Exec 'netsh advfirewall firewall add rule name="Driftcast Receiver" dir=in action=allow program="$INSTDIR\Driftcast Receiver.exe" enable=yes profile=any'
!macroend

!macro customUnInstall
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Driftcast Receiver"'
!macroend
