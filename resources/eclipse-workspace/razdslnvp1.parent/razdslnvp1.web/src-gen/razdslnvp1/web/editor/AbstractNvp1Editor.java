/**
 * @Generated by DSLFORGE
 */
package razdslnvp1.web.editor;

import org.dslforge.styledtext.BasicText;
import org.dslforge.xtext.common.XtextContentAssistEnabledEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import razdslnvp1.web.editor.widget.Nvp1;
import razdslnvp1.web.internal.Activator;

public abstract class AbstractNvp1Editor extends XtextContentAssistEnabledEditor {

	public AbstractNvp1Editor() {
		super();
		setLanguageName(Activator.RAZIE_DIESEL_NVP1);
		setInjector(Activator.getInstance().getInjector(Activator.RAZIE_DIESEL_NVP1));
	}
	
	@Override
	protected BasicText createTextWidget(Composite parent, int styles) {
		Nvp1 nvp1Widget = new Nvp1(parent, styles);
		GridData textLayoutData = new GridData();
		textLayoutData.horizontalAlignment = SWT.FILL;
		textLayoutData.verticalAlignment = SWT.FILL;
		textLayoutData.grabExcessHorizontalSpace = true;
		textLayoutData.grabExcessVerticalSpace = true;
		nvp1Widget.setLayoutData(textLayoutData);
		nvp1Widget.setEditable(true);
		return nvp1Widget;
	}
}
