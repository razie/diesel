/*Generated by Xtext*/
package razdslnvp1.web.contentassist.antlr;

import java.util.Collection;
import java.util.Map;
import java.util.HashMap;

import org.antlr.runtime.RecognitionException;
import org.eclipse.xtext.AbstractElement;
import org.eclipse.xtext.ui.editor.contentassist.antlr.AbstractContentAssistParser;
import org.eclipse.xtext.ui.editor.contentassist.antlr.FollowElement;
import org.eclipse.xtext.ui.editor.contentassist.antlr.internal.AbstractInternalContentAssistParser;

import com.google.inject.Inject;

import razie.diesel.services.Nvp1GrammarAccess;

public class Nvp1Parser extends AbstractContentAssistParser {
	
	@Inject
	private Nvp1GrammarAccess grammarAccess;
	
	private Map<AbstractElement, String> nameMappings;
	
	@Override
	protected razdslnvp1.web.contentassist.antlr.internal.InternalNvp1Parser createParser() {
		razdslnvp1.web.contentassist.antlr.internal.InternalNvp1Parser result = new razdslnvp1.web.contentassist.antlr.internal.InternalNvp1Parser(null);
		result.setGrammarAccess(grammarAccess);
		return result;
	}
	
	@Override
	protected String getRuleName(AbstractElement element) {
		if (nameMappings == null) {
			nameMappings = new HashMap<AbstractElement, String>() {
				private static final long serialVersionUID = 1L;
				{
					put(grammarAccess.getAbstractElementAccess().getAlternatives(), "rule__AbstractElement__Alternatives");
					put(grammarAccess.getExpectAccess().getAlternatives(), "rule__Expect__Alternatives");
					put(grammarAccess.getCheckExprAccess().getAlternatives(), "rule__CheckExpr__Alternatives");
					put(grammarAccess.getCheckExprAccess().getOpAlternatives_0_0_0(), "rule__CheckExpr__OpAlternatives_0_0_0");
					put(grammarAccess.getEXPRAccess().getAlternatives(), "rule__EXPR__Alternatives");
					put(grammarAccess.getFlowExprTAccess().getAlternatives(), "rule__FlowExprT__Alternatives");
					put(grammarAccess.getDataTypeAccess().getAlternatives(), "rule__DataType__Alternatives");
					put(grammarAccess.getMsgStereoAccess().getAlternatives(), "rule__MsgStereo__Alternatives");
					put(grammarAccess.getReceiveAccess().getGroup(), "rule__Receive__Group__0");
					put(grammarAccess.getReceiveAccess().getGroup_1(), "rule__Receive__Group_1__0");
					put(grammarAccess.getMsgAccess().getGroup(), "rule__Msg__Group__0");
					put(grammarAccess.getMsgAccess().getGroup_1(), "rule__Msg__Group_1__0");
					put(grammarAccess.getWhenAccess().getGroup(), "rule__When__Group__0");
					put(grammarAccess.getWhenAccess().getGroup_8(), "rule__When__Group_8__0");
					put(grammarAccess.getMatchAccess().getGroup(), "rule__Match__Group__0");
					put(grammarAccess.getMockAccess().getGroup(), "rule__Mock__Group__0");
					put(grammarAccess.getMockAccess().getGroup_7(), "rule__Mock__Group_7__0");
					put(grammarAccess.getFlowAccess().getGroup(), "rule__Flow__Group__0");
					put(grammarAccess.getConditionAccess().getGroup(), "rule__Condition__Group__0");
					put(grammarAccess.getExpectMAccess().getGroup(), "rule__ExpectM__Group__0");
					put(grammarAccess.getExpectMAccess().getGroup_1(), "rule__ExpectM__Group_1__0");
					put(grammarAccess.getExpectVAccess().getGroup(), "rule__ExpectV__Group__0");
					put(grammarAccess.getValAccess().getGroup(), "rule__Val__Group__0");
					put(grammarAccess.getVarAccess().getGroup(), "rule__Var__Group__0");
					put(grammarAccess.getOptionAccess().getGroup(), "rule__Option__Group__0");
					put(grammarAccess.getAttrChecksAccess().getGroup(), "rule__AttrChecks__Group__0");
					put(grammarAccess.getAttrChecksAccess().getGroup_1(), "rule__AttrChecks__Group_1__0");
					put(grammarAccess.getAttrChecksAccess().getGroup_1_1(), "rule__AttrChecks__Group_1_1__0");
					put(grammarAccess.getAttrCheckAccess().getGroup(), "rule__AttrCheck__Group__0");
					put(grammarAccess.getAttrCheckAccess().getGroup_1(), "rule__AttrCheck__Group_1__0");
					put(grammarAccess.getCheckExprAccess().getGroup_0(), "rule__CheckExpr__Group_0__0");
					put(grammarAccess.getCheckExprAccess().getGroup_1(), "rule__CheckExpr__Group_1__0");
					put(grammarAccess.getCheckExprAccess().getGroup_2(), "rule__CheckExpr__Group_2__0");
					put(grammarAccess.getCheckExprAccess().getGroup_3(), "rule__CheckExpr__Group_3__0");
					put(grammarAccess.getCheckExprAccess().getGroup_4(), "rule__CheckExpr__Group_4__0");
					put(grammarAccess.getCheckExprAccess().getGroup_5(), "rule__CheckExpr__Group_5__0");
					put(grammarAccess.getCheckExprAccess().getGroup_6(), "rule__CheckExpr__Group_6__0");
					put(grammarAccess.getCheckExprAccess().getGroup_7(), "rule__CheckExpr__Group_7__0");
					put(grammarAccess.getAttrSpecsAccess().getGroup(), "rule__AttrSpecs__Group__0");
					put(grammarAccess.getAttrSpecsAccess().getGroup_1(), "rule__AttrSpecs__Group_1__0");
					put(grammarAccess.getAttrSpecsAccess().getGroup_1_1(), "rule__AttrSpecs__Group_1_1__0");
					put(grammarAccess.getAttrSpecAccess().getGroup(), "rule__AttrSpec__Group__0");
					put(grammarAccess.getAttrSpecAccess().getGroup_1(), "rule__AttrSpec__Group_1__0");
					put(grammarAccess.getAttrSpecAccess().getGroup_2(), "rule__AttrSpec__Group_2__0");
					put(grammarAccess.getAttrAccess().getGroup(), "rule__Attr__Group__0");
					put(grammarAccess.getAttrAccess().getGroup_1(), "rule__Attr__Group_1__0");
					put(grammarAccess.getAttrAccess().getGroup_2(), "rule__Attr__Group_2__0");
					put(grammarAccess.getAttrsAccess().getGroup(), "rule__Attrs__Group__0");
					put(grammarAccess.getAttrsAccess().getGroup_1(), "rule__Attrs__Group_1__0");
					put(grammarAccess.getAttrsAccess().getGroup_1_1(), "rule__Attrs__Group_1_1__0");
					put(grammarAccess.getTopicAccess().getGroup(), "rule__Topic__Group__0");
					put(grammarAccess.getTopicAccess().getGroup_2(), "rule__Topic__Group_2__0");
					put(grammarAccess.getFlowExprAAccess().getGroup(), "rule__FlowExprA__Group__0");
					put(grammarAccess.getFlowExprAAccess().getGroup_1(), "rule__FlowExprA__Group_1__0");
					put(grammarAccess.getFlowExprPAccess().getGroup(), "rule__FlowExprP__Group__0");
					put(grammarAccess.getFlowExprPAccess().getGroup_1(), "rule__FlowExprP__Group_1__0");
					put(grammarAccess.getFlowExprTAccess().getGroup_1(), "rule__FlowExprT__Group_1__0");
					put(grammarAccess.getQualifiedNameWithWildCardAccess().getGroup(), "rule__QualifiedNameWithWildCard__Group__0");
					put(grammarAccess.getQualifiedNameAccess().getGroup(), "rule__QualifiedName__Group__0");
					put(grammarAccess.getQualifiedNameAccess().getGroup_1(), "rule__QualifiedName__Group_1__0");
					put(grammarAccess.getDomainModelAccess().getElementsAssignment(), "rule__DomainModel__ElementsAssignment");
					put(grammarAccess.getReceiveAccess().getStypeAssignment_1_1(), "rule__Receive__StypeAssignment_1_1");
					put(grammarAccess.getReceiveAccess().getNameAssignment_2(), "rule__Receive__NameAssignment_2");
					put(grammarAccess.getReceiveAccess().getAttrsAssignment_3(), "rule__Receive__AttrsAssignment_3");
					put(grammarAccess.getMsgAccess().getStypeAssignment_1_1(), "rule__Msg__StypeAssignment_1_1");
					put(grammarAccess.getMsgAccess().getNameAssignment_2(), "rule__Msg__NameAssignment_2");
					put(grammarAccess.getMsgAccess().getAttrsAssignment_3(), "rule__Msg__AttrsAssignment_3");
					put(grammarAccess.getWhenAccess().getAAssignment_1(), "rule__When__AAssignment_1");
					put(grammarAccess.getWhenAccess().getAaAssignment_2(), "rule__When__AaAssignment_2");
					put(grammarAccess.getWhenAccess().getCondAssignment_3(), "rule__When__CondAssignment_3");
					put(grammarAccess.getWhenAccess().getZAssignment_5(), "rule__When__ZAssignment_5");
					put(grammarAccess.getWhenAccess().getZaAssignment_6(), "rule__When__ZaAssignment_6");
					put(grammarAccess.getWhenAccess().getZAssignment_8_1(), "rule__When__ZAssignment_8_1");
					put(grammarAccess.getWhenAccess().getZaAssignment_8_2(), "rule__When__ZaAssignment_8_2");
					put(grammarAccess.getMatchAccess().getAAssignment_1(), "rule__Match__AAssignment_1");
					put(grammarAccess.getMatchAccess().getAaAssignment_2(), "rule__Match__AaAssignment_2");
					put(grammarAccess.getMatchAccess().getCondAssignment_3(), "rule__Match__CondAssignment_3");
					put(grammarAccess.getMockAccess().getAAssignment_1(), "rule__Mock__AAssignment_1");
					put(grammarAccess.getMockAccess().getAaAssignment_2(), "rule__Mock__AaAssignment_2");
					put(grammarAccess.getMockAccess().getCondAssignment_3(), "rule__Mock__CondAssignment_3");
					put(grammarAccess.getMockAccess().getZaAssignment_5(), "rule__Mock__ZaAssignment_5");
					put(grammarAccess.getMockAccess().getZaAssignment_7_1(), "rule__Mock__ZaAssignment_7_1");
					put(grammarAccess.getFlowAccess().getAAssignment_1(), "rule__Flow__AAssignment_1");
					put(grammarAccess.getFlowAccess().getAaAssignment_2(), "rule__Flow__AaAssignment_2");
					put(grammarAccess.getFlowAccess().getCondAssignment_3(), "rule__Flow__CondAssignment_3");
					put(grammarAccess.getFlowAccess().getExprAssignment_5(), "rule__Flow__ExprAssignment_5");
					put(grammarAccess.getConditionAccess().getAttrsAssignment_1(), "rule__Condition__AttrsAssignment_1");
					put(grammarAccess.getExpectMAccess().getNameAssignment_1_0(), "rule__ExpectM__NameAssignment_1_0");
					put(grammarAccess.getExpectMAccess().getAttrsAssignment_1_1(), "rule__ExpectM__AttrsAssignment_1_1");
					put(grammarAccess.getExpectMAccess().getCondAssignment_2(), "rule__ExpectM__CondAssignment_2");
					put(grammarAccess.getExpectVAccess().getPAssignment_1(), "rule__ExpectV__PAssignment_1");
					put(grammarAccess.getExpectVAccess().getCondAssignment_2(), "rule__ExpectV__CondAssignment_2");
					put(grammarAccess.getValAccess().getPAssignment_1(), "rule__Val__PAssignment_1");
					put(grammarAccess.getVarAccess().getPAssignment_1(), "rule__Var__PAssignment_1");
					put(grammarAccess.getOptionAccess().getAttrAssignment_1(), "rule__Option__AttrAssignment_1");
					put(grammarAccess.getAttrChecksAccess().getAttrsAssignment_1_0(), "rule__AttrChecks__AttrsAssignment_1_0");
					put(grammarAccess.getAttrChecksAccess().getAttrsAssignment_1_1_1(), "rule__AttrChecks__AttrsAssignment_1_1_1");
					put(grammarAccess.getAttrCheckAccess().getNameAssignment_0(), "rule__AttrCheck__NameAssignment_0");
					put(grammarAccess.getAttrCheckAccess().getTtypeAssignment_1_1(), "rule__AttrCheck__TtypeAssignment_1_1");
					put(grammarAccess.getAttrCheckAccess().getCheckAssignment_2(), "rule__AttrCheck__CheckAssignment_2");
					put(grammarAccess.getCheckExprAccess().getOpAssignment_0_0(), "rule__CheckExpr__OpAssignment_0_0");
					put(grammarAccess.getCheckExprAccess().getEexprAssignment_0_1(), "rule__CheckExpr__EexprAssignment_0_1");
					put(grammarAccess.getCheckExprAccess().getEexprAssignment_6_1(), "rule__CheckExpr__EexprAssignment_6_1");
					put(grammarAccess.getCheckExprAccess().getEexprAssignment_7_1(), "rule__CheckExpr__EexprAssignment_7_1");
					put(grammarAccess.getAttrSpecsAccess().getAttrsAssignment_1_0(), "rule__AttrSpecs__AttrsAssignment_1_0");
					put(grammarAccess.getAttrSpecsAccess().getAttrsAssignment_1_1_1(), "rule__AttrSpecs__AttrsAssignment_1_1_1");
					put(grammarAccess.getAttrSpecAccess().getNameAssignment_0(), "rule__AttrSpec__NameAssignment_0");
					put(grammarAccess.getAttrSpecAccess().getTtypeAssignment_1_1(), "rule__AttrSpec__TtypeAssignment_1_1");
					put(grammarAccess.getAttrSpecAccess().getEexprAssignment_2_1(), "rule__AttrSpec__EexprAssignment_2_1");
					put(grammarAccess.getAttrAccess().getNameAssignment_0(), "rule__Attr__NameAssignment_0");
					put(grammarAccess.getAttrAccess().getTtypeAssignment_1_1(), "rule__Attr__TtypeAssignment_1_1");
					put(grammarAccess.getAttrAccess().getEexprAssignment_2_1(), "rule__Attr__EexprAssignment_2_1");
					put(grammarAccess.getEXPRAccess().getParmAssignment_0(), "rule__EXPR__ParmAssignment_0");
					put(grammarAccess.getEXPRAccess().getSvalueAssignment_1(), "rule__EXPR__SvalueAssignment_1");
					put(grammarAccess.getEXPRAccess().getIvalueAssignment_2(), "rule__EXPR__IvalueAssignment_2");
					put(grammarAccess.getAttrsAccess().getAttrsAssignment_1_0(), "rule__Attrs__AttrsAssignment_1_0");
					put(grammarAccess.getAttrsAccess().getAttrsAssignment_1_1_1(), "rule__Attrs__AttrsAssignment_1_1_1");
					put(grammarAccess.getTopicAccess().getNameAssignment_1(), "rule__Topic__NameAssignment_1");
					put(grammarAccess.getTopicAccess().getTAssignment_2_1(), "rule__Topic__TAssignment_2_1");
					put(grammarAccess.getFlowExprAAccess().getAAssignment_0(), "rule__FlowExprA__AAssignment_0");
					put(grammarAccess.getFlowExprAAccess().getBAssignment_1_1(), "rule__FlowExprA__BAssignment_1_1");
					put(grammarAccess.getFlowExprPAccess().getAAssignment_0(), "rule__FlowExprP__AAssignment_0");
					put(grammarAccess.getFlowExprPAccess().getBAssignment_1_1(), "rule__FlowExprP__BAssignment_1_1");
					put(grammarAccess.getFlowExprTAccess().getMAssignment_0(), "rule__FlowExprT__MAssignment_0");
					put(grammarAccess.getDataTypeAccess().getStringAssignment_0(), "rule__DataType__StringAssignment_0");
					put(grammarAccess.getDataTypeAccess().getIntAssignment_1(), "rule__DataType__IntAssignment_1");
					put(grammarAccess.getDataTypeAccess().getDateAssignment_2(), "rule__DataType__DateAssignment_2");
					put(grammarAccess.getDataTypeAccess().getNumberAssignment_3(), "rule__DataType__NumberAssignment_3");
					put(grammarAccess.getDataTypeAccess().getArrayAssignment_4(), "rule__DataType__ArrayAssignment_4");
					put(grammarAccess.getDataTypeAccess().getJsonAssignment_5(), "rule__DataType__JsonAssignment_5");
					put(grammarAccess.getDataTypeAccess().getTtypeAssignment_6(), "rule__DataType__TtypeAssignment_6");
					put(grammarAccess.getMsgStereoAccess().getGETAssignment_0(), "rule__MsgStereo__GETAssignment_0");
					put(grammarAccess.getMsgStereoAccess().getPOSTAssignment_1(), "rule__MsgStereo__POSTAssignment_1");
					put(grammarAccess.getMsgStereoAccess().getCamelAssignment_2(), "rule__MsgStereo__CamelAssignment_2");
					put(grammarAccess.getMsgStereoAccess().getJSAssignment_3(), "rule__MsgStereo__JSAssignment_3");
					put(grammarAccess.getMsgStereoAccess().getJavaAssignment_4(), "rule__MsgStereo__JavaAssignment_4");
				}
			};
		}
		return nameMappings.get(element);
	}
	
	@Override
	protected Collection<FollowElement> getFollowElements(AbstractInternalContentAssistParser parser) {
		try {
			razdslnvp1.web.contentassist.antlr.internal.InternalNvp1Parser typedParser = (razdslnvp1.web.contentassist.antlr.internal.InternalNvp1Parser) parser;
			typedParser.entryRuleDomainModel();
			return typedParser.getFollowElements();
		} catch(RecognitionException ex) {
			throw new RuntimeException(ex);
		}		
	}
	
	@Override
	protected String[] getInitialHiddenTokens() {
		return new String[] { "RULE_WS", "RULE_COMMENT" };
	}
	
	public Nvp1GrammarAccess getGrammarAccess() {
		return this.grammarAccess;
	}
	
	public void setGrammarAccess(Nvp1GrammarAccess grammarAccess) {
		this.grammarAccess = grammarAccess;
	}
}