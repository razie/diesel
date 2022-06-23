package razie.diesel.samples;

import razie.diesel.samples.DomEngineUtils$;

/**
 * ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
public class JavaDieselSample {
    public static void main(String[] argv) {
        DomEngineUtils$.MODULE$.runMsgAsync("blinq", "$msg ctx.echo(x=123)");
    }
}
