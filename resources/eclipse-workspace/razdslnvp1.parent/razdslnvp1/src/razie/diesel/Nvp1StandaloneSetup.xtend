/*
 * generated by Xtext 2.19.0
 */
package razie.diesel


/**
 * Initialization support for running Xtext languages without Equinox extension registry.
 */
class Nvp1StandaloneSetup extends Nvp1StandaloneSetupGenerated {

	def static void doSetup() {
		new Nvp1StandaloneSetup().createInjectorAndDoEMFRegistration()
	}
}
