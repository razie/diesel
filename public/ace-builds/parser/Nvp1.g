/*Generated by DSLFORGE*/

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




// file extension .nvp1

rule_DomainModel:
    elements+=rule_AbstractElement* EOF!;




rule_AbstractElement:
    rule_Expect | rule_Msg | rule_When | rule_Option | rule_Val | rule_Mock;




//-------------------- raz

rule_Expect:
    rule_ExpectM | rule_ExpectV
;




rule_Condition:
    '$if' attrs=rule_AttrSpecs
;




rule_ExpectM:
    '$expect' ('$msg' name=rule_QualifiedName attrs=rule_AttrSpecs?) (cond=rule_Condition)?;




rule_ExpectV:
    '$expect' ('$val' p=rule_AttrSpec?) (cond=rule_Condition)?;




rule_Val:
    '$val' p=rule_AttrSpec
;




rule_Msg:
    '$msg' ('<' stype=rule_MsgStereo '>')? name=rule_QualifiedName attrs=rule_AttrSpecs?
;




rule_When:
	'$when' a=ID aa=rule_Attrs? cond=rule_Condition? '=>' z=ID za=rule_AttrSpecs? 
;




rule_Mock:
	'$mock' a=ID aa=rule_Attrs? cond=rule_Condition? '=>' za=rule_AttrSpecs?
;




rule_Option:
    '$opt' attr=rule_AttrSpec
;




rule_AttrSpec:
  name=ID (':' ttype=rule_DataType)? ('=' eexpr=rule_EXPR)?;




rule_Attr:
  name=ID (':' ttype=rule_DataType)? ('=' eexpr=rule_EXPR)?;




rule_EXPR:
  parm=ID | svalue=STRING | ivalue=INT;



  
rule_Attrs:
    ('(' (attrs+=rule_Attr (',' attrs+=rule_Attr)*)? ')')
;




rule_AttrSpecs:
    ('(' (attrs+=rule_AttrSpec (',' attrs+=rule_AttrSpec)*)? ')')
;




//TypeRef:
//    referenced=[Type|QualifiedName] multi?='*'?;

rule_QualifiedNameWithWildCard:
    rule_QualifiedName '.*'?;




rule_QualifiedName:
    ID ('.' ID)*;


rule_DataType:
	string='String' | int='Int' | date='Date';


rule_MsgStereo:
	gET='GET' | pOST='POST' | camel='Camel' | jS='JS' | java='Java';


ID : ('a'..'z' | 'A'..'Z' | '_') ('a'..'z' | 'A'..'Z' | '_' | '0'..'9')* ;

STRING : ('"' ('\\' ('b'|'t'|'n'|'f'|'r'|'u'|'"'|'\''|'\\')|~(('\\'|'"')))* '"'|'\'' ('\\' ('b'|'t'|'n'|'f'|'r'|'u'|'"'|'\''|'\\')|~(('\\'|'\'')))* '\'');

COMMENT : ('/*' .* '*/' | '//' ~('\r' | '\n')*)   { $channel = HIDDEN; } ;

WS:  (' '|'\r'|'\t'|'\u000C'|'\n') {$channel=HIDDEN;} ;

//NUMBER: INT ('.' INT)?;

INT: ('0'..'9')+;

