/**
 * generated by Xtext 2.19.0
 */
package razie.diesel.web;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.eclipse.xtext.util.Modules2;
import razie.diesel.Nvp1RuntimeModule;
import razie.diesel.Nvp1StandaloneSetup;
import razie.diesel.ide.Nvp1IdeModule;
import razie.diesel.web.Nvp1WebModule;

/**
 * Initialization support for running Xtext languages in web applications.
 */
@SuppressWarnings("all")
public class Nvp1WebSetup extends Nvp1StandaloneSetup {
  @Override
  public Injector createInjector() {
    Nvp1RuntimeModule _nvp1RuntimeModule = new Nvp1RuntimeModule();
    Nvp1IdeModule _nvp1IdeModule = new Nvp1IdeModule();
    Nvp1WebModule _nvp1WebModule = new Nvp1WebModule();
    return Guice.createInjector(Modules2.mixin(_nvp1RuntimeModule, _nvp1IdeModule, _nvp1WebModule));
  }
}
