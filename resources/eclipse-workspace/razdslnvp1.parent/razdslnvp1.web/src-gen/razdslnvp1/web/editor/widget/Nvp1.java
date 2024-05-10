/**
 * @Generated
 */
package razdslnvp1.web.editor.widget;

import java.util.ArrayList;
import java.util.List;

import org.dslforge.styledtext.BasicText;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.rap.rwt.remote.Connection;
import org.eclipse.rap.rwt.remote.RemoteObject;
import org.eclipse.swt.widgets.Composite;

public class Nvp1 extends BasicText {
	
	private static final long serialVersionUID = 1L;
	
	private static final String REMOTE_TYPE = "razdslnvp1.web.editor.widget.Nvp1";
	
	public Nvp1(Composite parent, int style) {
		super(parent, style);
	}
	
	@Override
	protected RemoteObject createRemoteObject(Connection connection) {
		return connection.createRemoteObject(REMOTE_TYPE);
	}
	
	@Override 
	protected void setupClient() {
		super.setupClient();
		List<IPath> languageResources = new ArrayList<IPath>();
		languageResources.add(new Path("src-js/razdslnvp1/web/ace/theme-nvp1.js"));
		languageResources.add(new Path("src-js/razdslnvp1/web/ace/mode-nvp1.js"));
		languageResources.add(new Path("src-js/razdslnvp1/web/ace/worker-nvp1.js"));
		languageResources.add(new Path("src-js/razdslnvp1/web/ace/snippets/nvp1.js"));
		languageResources.add(new Path("src-js/razdslnvp1/web/parser/antlr-all-min.js"));
		languageResources.add(new Path("src-js/razdslnvp1/web/parser/Nvp1Parser.js"));
		languageResources.add(new Path("src-js/razdslnvp1/web/parser/Nvp1Lexer.js"));
		registerJsResources(languageResources, getClassLoader());
		loadJsResources(languageResources);
	}

	@Override
	protected ClassLoader getClassLoader() {
		ClassLoader classLoader = Nvp1.class.getClassLoader();
		return classLoader;
	}
}
