/**
 * @Generated
 */
package razdslnvp1.web;

import org.dslforge.xtext.common.guice.AbstractGuiceAwareWebExecutableExtensionFactory;
import org.osgi.framework.Bundle;
import razdslnvp1.web.internal.Activator;

import com.google.inject.Injector;

public class Nvp1ExecutableExtensionFactory extends AbstractGuiceAwareWebExecutableExtensionFactory {

	@Override
	public Bundle getBundle() {
		return Activator.getInstance().getBundle();
	}
	
	@Override
	public Injector getInjector() {
		return Activator.getInstance().getInjector(Activator.RAZIE_DIESEL_NVP1);
	}
}
