/*
 * generated by Xtext 2.19.0
 */
package razie.diesel.validation;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.xtext.validation.AbstractDeclarativeValidator;

public abstract class AbstractNvp1Validator extends AbstractDeclarativeValidator {
	
	@Override
	protected List<EPackage> getEPackages() {
		List<EPackage> result = new ArrayList<EPackage>();
		result.add(razie.diesel.nvp1.Nvp1Package.eINSTANCE);
		return result;
	}
}
