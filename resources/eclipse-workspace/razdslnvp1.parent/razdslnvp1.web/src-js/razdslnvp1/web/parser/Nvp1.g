/**
 * @Generated
 */
grammar Nvp1;

options {
  language=JavaScript;
  output=AST;
  ASTLabelType=CommonTree;
}

@lexer::header {
}

@parser::header {
}




rule_DomainModel:
    elements_0+=rule_AbstractElement* EOF!;




rule_AbstractElement:
    rule_Expect | rule_Msg | rule_Match | rule_When | rule_Receive | rule_Flow | rule_Option | rule_Val | rule_Mock | rule_Topic | rule_Braq | TEXT;




//-------------------- raz

rule_Receive:
    ('$send') ('<' stype_0=rule_MsgStereo '>')? name_1=rule_MsgName attrs_2=rule_AttrSpecs? NEWLINE
;




rule_Msg:
    '$msg' ('<' stype_0=rule_MsgStereo '>')? name_1=rule_MsgName attrs_2=rule_AttrSpecs? NEWLINE
;



rule_MsgName:
    rule_QualifiedName
;




rule_When:
	'$when' a_0=ID aa_1=rule_Attrs? cond_2=rule_Condition? ARROW z_3=ID za_4=rule_AttrSpecs?  NEWLINE
	( FARROW z_5=ID za_6=rule_AttrSpecs? NEWLINE )*
;




rule_Match:
	'$match' a_0=ID aa_1=rule_Attrs? cond_2=rule_Condition?  NEWLINE
;




rule_Mock:
	'$mock' a_0=ID aa_1=rule_Attrs? cond_2=rule_Condition? ARROW za_3=rule_AttrSpecs? NEWLINE
	( FARROW za_4=rule_AttrSpecs? NEWLINE )* 
;




rule_Flow:
	'$flow' a_0=ID aa_1=rule_Attrs? cond_2=rule_Condition? ARROW expr_3=rule_FlowExprA  NEWLINE
;




rule_Expect:
    rule_ExpectM | rule_ExpectV
;




rule_Condition:
    '$if' attrs_0=rule_AttrChecks
;




rule_ExpectM:
    '$expect' (name_0=rule_QualifiedName attrs_1=rule_AttrChecks?) (cond_2=rule_Condition)?NEWLINE
;




rule_ExpectV:
    '$expect' p_0=rule_AttrChecks (cond_1=rule_Condition)?NEWLINE
;






rule_Val:
    '$val' p_0=rule_AttrSpec NEWLINE
;



rule_Var:
    '$var' p_0=rule_AttrSpec NEWLINE
;




rule_Option:
    '$opt' attr_0=rule_AttrSpec NEWLINE
;






rule_AttrChecks:
   '(' (attrs_0+=rule_AttrCheck (',' attrs_1+=rule_AttrCheck)*)? ')'
;






rule_AttrCheck:
  name_0=rule_QualifiedName (':' ttype_1=rule_DataType)? (check_2=rule_CheckExpr)?
;






rule_CheckExpr:
  (op_0=('=' | '!=' | '<' | '<=' | '>' | '>=' | '~=') eexpr_1=rule_EXPR) 
  | ('is'  'number')
  | ('is'  'defined')
  | ('is'  'empty')
  | ('not' 'defined')
  | ('not' 'empty')
  | ('is'  eexpr_2=rule_EXPR)
  | ('contains' eexpr_3=rule_EXPR)
;





rule_AttrSpecs:
   '(' (attrs_0+=rule_AttrSpec (',' attrs_1+=rule_AttrSpec)*)? ')'
;






rule_AttrSpec:
  name_0=rule_QualifiedName (':' ttype_1=rule_DataType)? ('=' eexpr_2=rule_EXPR)?;






rule_Attr:
  name_0=ID (':' ttype_1=rule_DataType)? ('=' eexpr_2=rule_EXPR)?;






rule_EXPR:
  parm_0=rule_QualifiedName | svalue_1=STRING | ivalue_2=INT;





//  parm=[Attr|QualifiedName] | svalue=STRING | ivalue=INT;
  
rule_Attrs:
    '(' (attrs_0+=rule_Attr (',' attrs_1+=rule_Attr)*)? ')'
;






rule_Topic:
    '[[' name_0=rule_QualifiedName (':' t_1=rule_QualifiedName)? ']]'
;






rule_Braq:
    '}'
;






rule_FlowExprA:
  a_0=rule_FlowExprP ( '+' b_1+=rule_FlowExprP)*
;




rule_FlowExprP:
  a_0=rule_FlowExprT ('|' b_1+=rule_FlowExprT)*
;




rule_FlowExprT:
  m_0=ID | '(' rule_FlowExprA ')'
;





//TypeRef:
//    referenced=[Type|QualifiedName] multi?='*'?;

rule_QualifiedNameWithWildCard:
    rule_QualifiedName '.*'?;




rule_QualifiedName:
    ID ('.' ID)*;




rule_DataType:
	string_0='String' | int_1='Int' | date_2='Date' | number_3='Number' | array_4='Array' | json_5 = 'JSON' | ttype_6=rule_QualifiedName ;




rule_MsgStereo:
	get_0='GET' | post_1='POST' | camel_2='Camel' | js_3='JS' | java_4='Java';



NEWLINE : ('\r' | '\n')+  ;



ARROW : '=>' | '==>' ;



FARROW : '=>' | '==>' | '-' | '->';



TEXT : .+ ;

ID : ('a'..'z' | 'A'..'Z' | '_') ('a'..'z' | 'A'..'Z' | '_' | '0'..'9')* ;

STRING : ('"' ('\\' ('b'|'t'|'n'|'f'|'r'|'u'|'"'|'\''|'\\')|~(('\\'|'"')))* '"'|'\'' ('\\' ('b'|'t'|'n'|'f'|'r'|'u'|'"'|'\''|'\\')|~(('\\'|'\'')))* '\'');

COMMENT : ('/*' .* '*/' | '//' ~('\r' | '\n')*)   { $channel = HIDDEN; } ;

WS:  (' '|'\r'|'\t'|'\u000C'|'\n') {$channel=HIDDEN;} ;

INT: ('0'..'9')+;

