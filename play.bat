set _JAVA_OPTIONS="-Xmx14000m"
set rrr="-XX:+CMSClassUnloadingEnabled"
c:\bin\play-2.1.5\play -Ddevmode=yeah -Dlogger.file=c:\cygwin\home\razvanc\w\racerkidz\conf\logback-test.xml -Drk.properties=c:\cygwin\home\razvanc\w\racerkidz\rk.properties -Dcom.sun.management.jmxremote.port=9001 -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmx.remote.authenticate=false -Dcom.sun.management.jmxremote.access.file=c:\cygwin\home\razvanc\w\racerkidz\jmxr.a -Dcom.sun.management.jmxremote.password.file=c:\cygwin\home\razvanc\w\racerkidz\jmxr.p %1 %2 %3
