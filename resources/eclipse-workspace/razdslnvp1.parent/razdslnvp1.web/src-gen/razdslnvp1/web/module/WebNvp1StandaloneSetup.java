/**
 * @Generated
 */
package razdslnvp1.web.module;

import org.eclipse.xtext.util.Modules2;
import razie.diesel.Nvp1RuntimeModule;
import razie.diesel.Nvp1StandaloneSetup;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;

public class WebNvp1StandaloneSetup extends Nvp1StandaloneSetup {

	public static void doSetup() {
		new Nvp1StandaloneSetup().createInjectorAndDoEMFRegistration();
	}

	public Injector createInjector(String language) {
		try {
			Module runtimeModule = getRuntimeModule();
			Injector injector = Guice.createInjector(runtimeModule);
			register(injector);
			return injector;
		} catch (Exception e) {
			System.err.println("Failed to create injector for " + language);
			throw new RuntimeException("Failed to create injector for "
					+ language, e);
		}
	}

	private Module getRuntimeModule() {
		org.eclipse.xtext.common.TerminalsStandaloneSetup.doSetup();
		Nvp1RuntimeModule original = new Nvp1RuntimeModule();
		WebNvp1RuntimeModule module = new WebNvp1RuntimeModule();
		Module mergedModule = Modules2.mixin((Module) original, module);
		return mergedModule;
	}
}
