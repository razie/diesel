/*
 * generated by Xtext 2.19.0
 */
package razie.diesel.parser.antlr;

import java.io.InputStream;
import org.eclipse.xtext.parser.antlr.IAntlrTokenFileProvider;

public class Nvp1AntlrTokenFileProvider implements IAntlrTokenFileProvider {

	@Override
	public InputStream getAntlrTokenFile() {
		ClassLoader classLoader = getClass().getClassLoader();
		return classLoader.getResourceAsStream("razie/diesel/parser/antlr/internal/InternalNvp1.tokens");
	}
}