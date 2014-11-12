#set _JAVA_OPTIONS="-Xmx2400m"
set _JAVA_OPTIONS="-Xss1500k"
set rrr="-XX:+CMSClassUnloadingEnabled"
d:\bin\play-2.2.2\play -Ddevmode=yeah -Dlogger.file=c:\cygwin\home\razvanc\w\racerkidz\conf\logback-test.xml -Drk.properties=c:\cygwin\home\razvanc\w\racerkidz\rk.properties -Dcom.sun.management.jmxremote.port=9001 -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmx.remote.authenticate=false -Dcom.sun.management.jmxremote.access.file=c:\cygwin\home\razvanc\w\racerkidz\jmxr.a -Dcom.sun.management.jmxremote.password.file=c:\cygwin\home\razvanc\w\racerkidz\jmxr.p %1 %2 %3
