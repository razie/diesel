grammar g1;

main: 'a'|'b' ;

prog: (expr_or_assign (';' | NL) | NL)* EOF;

expr_or_assign
    :   expr ('<-'|'='|'<<-') expr_or_assign
    |   expr
    ;

dom: (domclass)*;

domclass: 'class' ID '(' formlist? ')' ('extends' ID)
;

expr:   dom
| expr ('::'|':::') expr
    |   expr ('$'|'@') expr
    |   ('-'|'+') expr
    |   expr ':' expr
    |   expr ('*'|'/') expr
    |   expr ('+'|'-') expr
    |   expr ('>'|'>='|'<'|'<='|'=='|'!=') expr
    |   '!' expr
    |   expr ('&'|'&&') expr
    |   expr ('|'|'||') expr
    |   '~' expr
    |   expr '~' expr
    |   expr ('->'|'->>'|':=') expr
    |   'function' '(' formlist? ')' expr // define function
    |   '{' exprlist '}' // compound statement
    |   'if' '(' expr ')' expr
    |   'if' '(' expr ')' expr 'else' expr
    |   'for' '(' ID 'in' expr ')' expr
    |   'while' '(' expr ')' expr
    |   'repeat' expr
    |   '?' expr // get help on expr, usually string or ID
    |   'next'
    |   'break'
    |   '(' expr ')'
    |   ID
    |   STRING
    |   NUM
    ;

exprlist:   expr_or_assign ((';'|NL) expr_or_assign?)*
    |
    ;

formlist : form (',' form)* ;

form:   ID
    |   ID '=' expr
    |   '...'
    ;

nvpSpec : attr (',' attr)* ;

attr : ID | ID ':' ID | ID '=' expr ;

NL      :   '\r'? '\n' ;

WS      :   [ \t]+ -> skip ;

ID  :   '.' (LETTER|'_'|'.') (LETTER|DIGIT|'_'|'.')*
    |   LETTER (LETTER|DIGIT|'_'|'.')*
    ;

NUM  :   DIGIT(DIGIT)*
    ;

fragment LETTER  : [a-zA-Z] ;

fragment DIGIT:  '0'..'9' ;

STRING
    :   '"' ( ~[\\"] )*? '"'
    |   '\'' ( ~[\\'] )*? '\''
    ;


