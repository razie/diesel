/**
 * @Generated
 */
grammar Nvp2;

options {
  language=JavaScript;
  output=AST;
  ASTLabelType=CommonTree;
}

@lexer::header {
}

@parser::header {
}


rule_Nvp2:
    elements+=rule_AbstractElement* EOF!;

rule_AbstractElement:
    rule_Expect | rule_Msg | rule_Match | rule_When | rule_When2 | rule_Gen |
    rule_Receive | 
    rule_Flow | rule_Option | rule_Val | rule_Var | rule_Mock | rule_Topic | 
    rule_Anno |  rule_Object | rule_Class | rule_Assoc | rule_Def |
    rule_Assert |
    rule_Braq | TEXT;


//-------------------- Rules

rule_Receive:
    '$send' ('<' stype=rule_MsgStereo '>')? name=rule_MsgName attrs=rule_AttrSpecs? NEWLINE
;

rule_Msg:
    '$msg' ('<' stype=rule_MsgStereo '>')? name=rule_MsgName attrs=rule_AttrSpecs? NEWLINE
;

rule_Gen:
	rule_GenMsg | rule_GenPas | rule_If2 | rule_Else2 | rule_If | rule_Else
;
	
rule_GenMsg:
    ARROW ('<' stype=rule_MsgStereo '>')? name=rule_MsgName attrs=rule_AttrSpecs? NEWLINE
;

rule_GenPas:
    attrs=rule_AttrSpecs NEWLINE
;

rule_If:
    '$if' cond=rule_AttrChecks name=rule_MsgName attrs=rule_AttrSpecs? NEWLINE
;

rule_Else:
    '$else' name=rule_MsgName attrs=rule_AttrSpecs? NEWLINE
;

rule_If2:
    '$if' '{' NEWLINE
	rule_Gen*
    '}'
;

rule_Else2:
    '$else' '{' NEWLINE
	rule_Gen*
    '}'
;

rule_When:
	'$when' a=rule_MsgName aa=rule_Attrs? cond=rule_Condition? (ARROW z=rule_MsgName za=rule_AttrSpecs)?  NEWLINE
	rule_Gen*
;

rule_When2:
	'$when' a=rule_MsgName aa=rule_Attrs? cond=rule_Condition? '{' NEWLINE
	rule_Gen*
	'}'
;

rule_Match:
	'$match' a=ID aa=rule_Attrs? cond=rule_Condition?  NEWLINE
;

rule_Mock:
	'$mock' a=ID aa=rule_Attrs? cond=rule_Condition? '=>' za=rule_AttrSpecs? NEWLINE
	( '=>' za=rule_AttrSpecs? NEWLINE )* 
;

rule_Flow:
	'$flow' a=ID aa=rule_Attrs? cond=rule_Condition? '=>' expr=rule_FlowExprA  NEWLINE
;

rule_Expect:
    rule_ExpectM | rule_ExpectV
;

rule_Condition:
    '$if' attrs=rule_AttrChecks |
    '$else' attrs=rule_AttrChecks
;

rule_ExpectM:
    '$expect' (name=rule_QualifiedName attrs=rule_AttrChecks?) (cond=rule_Condition)?NEWLINE
;

rule_ExpectV:
    '$expect' p=rule_AttrChecks (cond=rule_Condition)?NEWLINE
;

rule_Val:
    '$val' p=rule_AttrSpec NEWLINE
;

rule_Var:
    '$var' p=rule_AttrSpec NEWLINE
;

rule_Option:
    '$opt' attr=rule_AttrSpec NEWLINE
;

//=============== domain

rule_Class:
    '$class' 
    ('[' stype=rule_CommaList ']')? 
    name=rule_QualifiedName 
    attrs=rule_AttrSpecs?
    ('extends' sstype=rule_CommaList )? 
    ('<' stype=rule_CommaList '>')? 
    ('{' -> '}')?
    NEWLINE
;

rule_Object:
    '$object' name=rule_QualifiedName clsname=rule_QualifiedName attrs=rule_AttrSpecs? NEWLINE
;

rule_Def:
    '$def' 
    name=rule_QualifiedName 
    attrs=rule_AttrSpecs? 
    (':' stype=rule_QualifiedName)? 
    ('{{' -> '}}')?
    NEWLINE
;

rule_Anno:
    '$anno' attrs=rule_AttrSpecs? NEWLINE
;
rule_Assoc:
    '$assoc' aname=rule_QualifiedName '\:' arole=rule_QualifiedName '->' zname=rule_QualifiedName '\:' zname=rule_QualifiedName NEWLINE
;
rule_Assert:
    '$assert' name=rule_QualifiedName attrs=rule_AttrChecks? NEWLINE
;

//============== misc

rule_AttrChecks:
   '(' ( attrs += rule_AttrCheck (',' attrs += rule_AttrCheck )* )? ')'
;

rule_AttrCheck:
  name=rule_QualifiedName (':' ttype=rule_DataType)? (check=rule_CheckExpr)?
;


rule_CheckExpr:
  (op=('=' | '!=' | '<' | '<=' | '>' | '>=' | '~=') eexpr=rule_EXPR) 
  | ('is' 'number')
  | ('is' eexpr=rule_EXPR)
  | ('contains' eexpr=rule_EXPR)
;


rule_AttrSpecs:
   '(' (attrs+=rule_AttrSpec (',' attrs+=rule_AttrSpec)*)? ')'
;

rule_AttrSpec:
  name = ('@' ttype=rule_AttrAnno)* rule_QualifiedName (':' ttype=rule_DataType)? ('=' eexpr=rule_EXPR)?;

rule_Attr:
  name=ID (':' ttype=rule_DataType)? ('=' eexpr=rule_EXPR)?;

rule_EXPR:
  parm=rule_QualifiedName | svalue=STRING | ivalue=INT;

//  parm=[Attr|QualifiedName] | svalue=STRING | ivalue=INT;
  
rule_Attrs:
    '(' (attrs+=rule_Attr (',' attrs+=rule_Attr)*)? ')'
;

rule_Topic:
    '[[' name=rule_QualifiedName (':' t=rule_QualifiedName)? ']]'
;

rule_Braq:
    '}'
;

rule_FlowExprA:
  a=rule_FlowExprP ( '+' b+=rule_FlowExprP)*
;

rule_FlowExprP:
  a=rule_FlowExprT ('|' b+=rule_FlowExprT)*
;

rule_FlowExprT:
  m=ID | '(' rule_FlowExprA ')'
;


//TypeRef:
//    referenced=[Type|QualifiedName] multi?='*'?;

rule_QualifiedNameWithWildCard:
    rule_QualifiedName '.*'?;

rule_QualifiedName:
    ID ('.' ID)*;

rule_AttrAnno:
	key='key' | excache='excache';

rule_DataType:
	string='String' | int='Int' | date='Date' | number='Number';

rule_CommaList:
    rule_QualifiedName (',' rule_QualifiedName)*;

rule_MsgName:
    rule_QualifiedName
;

rule_MsgStereo:
    rule_MsgStereoElem (',' rule_MsgStereoElem)*;

rule_MsgStereoElem:
	gET='GET' | pOST='POST' | camel='Camel' | jS='JS' | java='Java'|
	pUblic='public' | pRivate='private' | rule_QualifiedName ;

SIMPLE_ARROW: ( '=>' | '==>' | '<=>' ) ;

ARROW: ( '|'* SIMPLE_ARROW) ;

ID: ('a'..'z' | 'A'..'Z' | '_') ('a'..'z' | 'A'..'Z' | '_' | '0'..'9')* ;

STRING: ('"' ('\\' ('b'|'t'|'n'|'f'|'r'|'u'|'"'|'\''|'\\')|~(('\\'|'"')))* '"'|'\'' ('\\' ('b'|'t'|'n'|'f'|'r'|'u'|'"'|'\''|'\\')|~(('\\'|'\'')))* '\'');

COMMENT: ('/*' .* '*/' | '//' ~('\r' | '\n')*)   { $channel = HIDDEN; } ;

WS:  (' '|'\r'|'\t'|'\u000C'|'\n') {$channel=HIDDEN;} ;

//NUMBER: INT ('.' INT)?;

INT: ('0'..'9')+;

