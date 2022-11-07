// $ANTLR 3.3 avr. 19, 2016 01:13:22 /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g 2016-06-03 10:38:23



var ExampleLexer = function(input, state) {
// alternate constructor @todo
// public ExampleLexer(CharStream input)
// public ExampleLexer(CharStream input, RecognizerSharedState state) {
    if (!state) {
        state = new org.antlr.runtime.RecognizerSharedState();
    }

    (function(){
    }).call(this);

    this.dfa10 = new ExampleLexer.DFA10(this);
    ExampleLexer.superclass.constructor.call(this, input, state);


};

org.antlr.lang.augmentObject(ExampleLexer, {
    EOF: -1,
    T__11: 11,
    Person: 4,
    NL: 5,
    ID: 6,
    STRING: 7,
    COMMENT: 8,
    WS: 9,
    INT: 10
});

(function(){
var HIDDEN = org.antlr.runtime.Token.HIDDEN_CHANNEL,
    EOF = org.antlr.runtime.Token.EOF;
org.antlr.lang.extend(ExampleLexer, org.antlr.runtime.Lexer, {
    EOF : -1,
    T__11 : 11,
    Person : 4,
    NL : 5,
    ID : 6,
    STRING : 7,
    COMMENT : 8,
    WS : 9,
    INT : 10,
    getGrammarFileName: function() { return "/Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g"; }
});
org.antlr.lang.augmentObject(ExampleLexer.prototype, {
    // $ANTLR start T__11
    mT__11: function()  {
        try {
            var _type = this.T__11;
            var _channel = org.antlr.runtime.BaseRecognizer.DEFAULT_TOKEN_CHANNEL;
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:10:7: ( 'Hello' )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:10:9: 'Hello'
            this.match("Hello"); 




            this.state.type = _type;
            this.state.channel = _channel;
        }
        finally {
        }
    },
    // $ANTLR end "T__11",

    // $ANTLR start Person
    mPerson: function()  {
        try {
            var _type = this.Person;
            var _channel = org.antlr.runtime.BaseRecognizer.DEFAULT_TOKEN_CHANNEL;
            var person=null;
            var list_person=null;
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:25:8: ( 'def' person+= ID NL )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:26:2: 'def' person+= ID NL
            this.match("def"); 

            var personStart45 = this.getCharIndex();
            this.mID(); 
            person = new org.antlr.runtime.CommonToken(this.input, org.antlr.runtime.Token.INVALID_TOKEN_TYPE, org.antlr.runtime.Token.DEFAULT_CHANNEL, personStart45, this.getCharIndex()-1);
            if (org.antlr.lang.isNull(list_person)) list_person = [];
            list_person.push(person);

            this.mNL(); 



            this.state.type = _type;
            this.state.channel = _channel;
        }
        finally {
        }
    },
    // $ANTLR end "Person",

    // $ANTLR start ID
    mID: function()  {
        try {
            var _type = this.ID;
            var _channel = org.antlr.runtime.BaseRecognizer.DEFAULT_TOKEN_CHANNEL;
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:29:4: ( ( 'a' .. 'z' | 'A' .. 'Z' | '_' ) ( 'a' .. 'z' | 'A' .. 'Z' | '_' | '0' .. '9' )* )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:29:6: ( 'a' .. 'z' | 'A' .. 'Z' | '_' ) ( 'a' .. 'z' | 'A' .. 'Z' | '_' | '0' .. '9' )*
            if ( (this.input.LA(1)>='A' && this.input.LA(1)<='Z')||this.input.LA(1)=='_'||(this.input.LA(1)>='a' && this.input.LA(1)<='z') ) {
                this.input.consume();

            }
            else {
                var mse = new org.antlr.runtime.MismatchedSetException(null,this.input);
                this.recover(mse);
                throw mse;}

            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:29:34: ( 'a' .. 'z' | 'A' .. 'Z' | '_' | '0' .. '9' )*
            loop1:
            do {
                var alt1=2;
                var LA1_0 = this.input.LA(1);

                if ( ((LA1_0>='0' && LA1_0<='9')||(LA1_0>='A' && LA1_0<='Z')||LA1_0=='_'||(LA1_0>='a' && LA1_0<='z')) ) {
                    alt1=1;
                }


                switch (alt1) {
                case 1 :
                    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:
                    if ( (this.input.LA(1)>='0' && this.input.LA(1)<='9')||(this.input.LA(1)>='A' && this.input.LA(1)<='Z')||this.input.LA(1)=='_'||(this.input.LA(1)>='a' && this.input.LA(1)<='z') ) {
                        this.input.consume();

                    }
                    else {
                        var mse = new org.antlr.runtime.MismatchedSetException(null,this.input);
                        this.recover(mse);
                        throw mse;}



                    break;

                default :
                    break loop1;
                }
            } while (true);




            this.state.type = _type;
            this.state.channel = _channel;
        }
        finally {
        }
    },
    // $ANTLR end "ID",

    // $ANTLR start STRING
    mSTRING: function()  {
        try {
            var _type = this.STRING;
            var _channel = org.antlr.runtime.BaseRecognizer.DEFAULT_TOKEN_CHANNEL;
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:31:8: ( ( '\"' ( '\\\\' ( 'b' | 't' | 'n' | 'f' | 'r' | 'u' | '\"' | '\\'' | '\\\\' ) | ~ ( ( '\\\\' | '\"' ) ) )* '\"' | '\\'' ( '\\\\' ( 'b' | 't' | 'n' | 'f' | 'r' | 'u' | '\"' | '\\'' | '\\\\' ) | ~ ( ( '\\\\' | '\\'' ) ) )* '\\'' ) )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:31:10: ( '\"' ( '\\\\' ( 'b' | 't' | 'n' | 'f' | 'r' | 'u' | '\"' | '\\'' | '\\\\' ) | ~ ( ( '\\\\' | '\"' ) ) )* '\"' | '\\'' ( '\\\\' ( 'b' | 't' | 'n' | 'f' | 'r' | 'u' | '\"' | '\\'' | '\\\\' ) | ~ ( ( '\\\\' | '\\'' ) ) )* '\\'' )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:31:10: ( '\"' ( '\\\\' ( 'b' | 't' | 'n' | 'f' | 'r' | 'u' | '\"' | '\\'' | '\\\\' ) | ~ ( ( '\\\\' | '\"' ) ) )* '\"' | '\\'' ( '\\\\' ( 'b' | 't' | 'n' | 'f' | 'r' | 'u' | '\"' | '\\'' | '\\\\' ) | ~ ( ( '\\\\' | '\\'' ) ) )* '\\'' )
            var alt4=2;
            var LA4_0 = this.input.LA(1);

            if ( (LA4_0=='\"') ) {
                alt4=1;
            }
            else if ( (LA4_0=='\'') ) {
                alt4=2;
            }
            else {
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 4, 0, this.input);

                throw nvae;
            }
            switch (alt4) {
                case 1 :
                    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:31:11: '\"' ( '\\\\' ( 'b' | 't' | 'n' | 'f' | 'r' | 'u' | '\"' | '\\'' | '\\\\' ) | ~ ( ( '\\\\' | '\"' ) ) )* '\"'
                    this.match('\"'); 
                    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:31:15: ( '\\\\' ( 'b' | 't' | 'n' | 'f' | 'r' | 'u' | '\"' | '\\'' | '\\\\' ) | ~ ( ( '\\\\' | '\"' ) ) )*
                    loop2:
                    do {
                        var alt2=3;
                        var LA2_0 = this.input.LA(1);

                        if ( (LA2_0=='\\') ) {
                            alt2=1;
                        }
                        else if ( ((LA2_0>='\u0000' && LA2_0<='!')||(LA2_0>='#' && LA2_0<='[')||(LA2_0>=']' && LA2_0<='\uFFFF')) ) {
                            alt2=2;
                        }


                        switch (alt2) {
                        case 1 :
                            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:31:16: '\\\\' ( 'b' | 't' | 'n' | 'f' | 'r' | 'u' | '\"' | '\\'' | '\\\\' )
                            this.match('\\'); 
                            if ( this.input.LA(1)=='\"'||this.input.LA(1)=='\''||this.input.LA(1)=='\\'||this.input.LA(1)=='b'||this.input.LA(1)=='f'||this.input.LA(1)=='n'||this.input.LA(1)=='r'||(this.input.LA(1)>='t' && this.input.LA(1)<='u') ) {
                                this.input.consume();

                            }
                            else {
                                var mse = new org.antlr.runtime.MismatchedSetException(null,this.input);
                                this.recover(mse);
                                throw mse;}



                            break;
                        case 2 :
                            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:31:61: ~ ( ( '\\\\' | '\"' ) )
                            if ( (this.input.LA(1)>='\u0000' && this.input.LA(1)<='!')||(this.input.LA(1)>='#' && this.input.LA(1)<='[')||(this.input.LA(1)>=']' && this.input.LA(1)<='\uFFFF') ) {
                                this.input.consume();

                            }
                            else {
                                var mse = new org.antlr.runtime.MismatchedSetException(null,this.input);
                                this.recover(mse);
                                throw mse;}



                            break;

                        default :
                            break loop2;
                        }
                    } while (true);

                    this.match('\"'); 


                    break;
                case 2 :
                    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:31:81: '\\'' ( '\\\\' ( 'b' | 't' | 'n' | 'f' | 'r' | 'u' | '\"' | '\\'' | '\\\\' ) | ~ ( ( '\\\\' | '\\'' ) ) )* '\\''
                    this.match('\''); 
                    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:31:86: ( '\\\\' ( 'b' | 't' | 'n' | 'f' | 'r' | 'u' | '\"' | '\\'' | '\\\\' ) | ~ ( ( '\\\\' | '\\'' ) ) )*
                    loop3:
                    do {
                        var alt3=3;
                        var LA3_0 = this.input.LA(1);

                        if ( (LA3_0=='\\') ) {
                            alt3=1;
                        }
                        else if ( ((LA3_0>='\u0000' && LA3_0<='&')||(LA3_0>='(' && LA3_0<='[')||(LA3_0>=']' && LA3_0<='\uFFFF')) ) {
                            alt3=2;
                        }


                        switch (alt3) {
                        case 1 :
                            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:31:87: '\\\\' ( 'b' | 't' | 'n' | 'f' | 'r' | 'u' | '\"' | '\\'' | '\\\\' )
                            this.match('\\'); 
                            if ( this.input.LA(1)=='\"'||this.input.LA(1)=='\''||this.input.LA(1)=='\\'||this.input.LA(1)=='b'||this.input.LA(1)=='f'||this.input.LA(1)=='n'||this.input.LA(1)=='r'||(this.input.LA(1)>='t' && this.input.LA(1)<='u') ) {
                                this.input.consume();

                            }
                            else {
                                var mse = new org.antlr.runtime.MismatchedSetException(null,this.input);
                                this.recover(mse);
                                throw mse;}



                            break;
                        case 2 :
                            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:31:132: ~ ( ( '\\\\' | '\\'' ) )
                            if ( (this.input.LA(1)>='\u0000' && this.input.LA(1)<='&')||(this.input.LA(1)>='(' && this.input.LA(1)<='[')||(this.input.LA(1)>=']' && this.input.LA(1)<='\uFFFF') ) {
                                this.input.consume();

                            }
                            else {
                                var mse = new org.antlr.runtime.MismatchedSetException(null,this.input);
                                this.recover(mse);
                                throw mse;}



                            break;

                        default :
                            break loop3;
                        }
                    } while (true);

                    this.match('\''); 


                    break;

            }




            this.state.type = _type;
            this.state.channel = _channel;
        }
        finally {
        }
    },
    // $ANTLR end "STRING",

    // $ANTLR start COMMENT
    mCOMMENT: function()  {
        try {
            var _type = this.COMMENT;
            var _channel = org.antlr.runtime.BaseRecognizer.DEFAULT_TOKEN_CHANNEL;
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:33:9: ( ( '/*' ( . )* '*/' | '//' (~ ( '\\r' | '\\n' ) )* ) )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:33:11: ( '/*' ( . )* '*/' | '//' (~ ( '\\r' | '\\n' ) )* )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:33:11: ( '/*' ( . )* '*/' | '//' (~ ( '\\r' | '\\n' ) )* )
            var alt7=2;
            var LA7_0 = this.input.LA(1);

            if ( (LA7_0=='/') ) {
                var LA7_1 = this.input.LA(2);

                if ( (LA7_1=='*') ) {
                    alt7=1;
                }
                else if ( (LA7_1=='/') ) {
                    alt7=2;
                }
                else {
                    var nvae =
                        new org.antlr.runtime.NoViableAltException("", 7, 1, this.input);

                    throw nvae;
                }
            }
            else {
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 7, 0, this.input);

                throw nvae;
            }
            switch (alt7) {
                case 1 :
                    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:33:12: '/*' ( . )* '*/'
                    this.match("/*"); 

                    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:33:17: ( . )*
                    loop5:
                    do {
                        var alt5=2;
                        var LA5_0 = this.input.LA(1);

                        if ( (LA5_0=='*') ) {
                            var LA5_1 = this.input.LA(2);

                            if ( (LA5_1=='/') ) {
                                alt5=2;
                            }
                            else if ( ((LA5_1>='\u0000' && LA5_1<='.')||(LA5_1>='0' && LA5_1<='\uFFFF')) ) {
                                alt5=1;
                            }


                        }
                        else if ( ((LA5_0>='\u0000' && LA5_0<=')')||(LA5_0>='+' && LA5_0<='\uFFFF')) ) {
                            alt5=1;
                        }


                        switch (alt5) {
                        case 1 :
                            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:33:17: .
                            this.matchAny(); 


                            break;

                        default :
                            break loop5;
                        }
                    } while (true);

                    this.match("*/"); 



                    break;
                case 2 :
                    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:33:27: '//' (~ ( '\\r' | '\\n' ) )*
                    this.match("//"); 

                    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:33:32: (~ ( '\\r' | '\\n' ) )*
                    loop6:
                    do {
                        var alt6=2;
                        var LA6_0 = this.input.LA(1);

                        if ( ((LA6_0>='\u0000' && LA6_0<='\t')||(LA6_0>='\u000B' && LA6_0<='\f')||(LA6_0>='\u000E' && LA6_0<='\uFFFF')) ) {
                            alt6=1;
                        }


                        switch (alt6) {
                        case 1 :
                            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:33:32: ~ ( '\\r' | '\\n' )
                            if ( (this.input.LA(1)>='\u0000' && this.input.LA(1)<='\t')||(this.input.LA(1)>='\u000B' && this.input.LA(1)<='\f')||(this.input.LA(1)>='\u000E' && this.input.LA(1)<='\uFFFF') ) {
                                this.input.consume();

                            }
                            else {
                                var mse = new org.antlr.runtime.MismatchedSetException(null,this.input);
                                this.recover(mse);
                                throw mse;}



                            break;

                        default :
                            break loop6;
                        }
                    } while (true);



                    break;

            }

             _channel = HIDDEN; 



            this.state.type = _type;
            this.state.channel = _channel;
        }
        finally {
        }
    },
    // $ANTLR end "COMMENT",

    // $ANTLR start WS
    mWS: function()  {
        try {
            var _type = this.WS;
            var _channel = org.antlr.runtime.BaseRecognizer.DEFAULT_TOKEN_CHANNEL;
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:35:3: ( ( ' ' | '\\r' | '\\t' | '\\u000C' | '\\n' ) )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:35:6: ( ' ' | '\\r' | '\\t' | '\\u000C' | '\\n' )
            if ( (this.input.LA(1)>='\t' && this.input.LA(1)<='\n')||(this.input.LA(1)>='\f' && this.input.LA(1)<='\r')||this.input.LA(1)==' ' ) {
                this.input.consume();

            }
            else {
                var mse = new org.antlr.runtime.MismatchedSetException(null,this.input);
                this.recover(mse);
                throw mse;}

            _channel=HIDDEN;



            this.state.type = _type;
            this.state.channel = _channel;
        }
        finally {
        }
    },
    // $ANTLR end "WS",

    // $ANTLR start INT
    mINT: function()  {
        try {
            var _type = this.INT;
            var _channel = org.antlr.runtime.BaseRecognizer.DEFAULT_TOKEN_CHANNEL;
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:39:4: ( ( '0' .. '9' )+ )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:39:6: ( '0' .. '9' )+
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:39:6: ( '0' .. '9' )+
            var cnt8=0;
            loop8:
            do {
                var alt8=2;
                var LA8_0 = this.input.LA(1);

                if ( ((LA8_0>='0' && LA8_0<='9')) ) {
                    alt8=1;
                }


                switch (alt8) {
                case 1 :
                    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:39:7: '0' .. '9'
                    this.matchRange('0','9'); 


                    break;

                default :
                    if ( cnt8 >= 1 ) {
                        break loop8;
                    }
                        var eee = new org.antlr.runtime.EarlyExitException(8, this.input);
                        throw eee;
                }
                cnt8++;
            } while (true);




            this.state.type = _type;
            this.state.channel = _channel;
        }
        finally {
        }
    },
    // $ANTLR end "INT",

    // $ANTLR start NL
    mNL: function()  {
        try {
            var _type = this.NL;
            var _channel = org.antlr.runtime.BaseRecognizer.DEFAULT_TOKEN_CHANNEL;
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:41:3: ( ( ( '\\r' )? '\\n' ) )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:41:5: ( ( '\\r' )? '\\n' )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:41:5: ( ( '\\r' )? '\\n' )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:41:6: ( '\\r' )? '\\n'
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:41:6: ( '\\r' )?
            var alt9=2;
            var LA9_0 = this.input.LA(1);

            if ( (LA9_0=='\r') ) {
                alt9=1;
            }
            switch (alt9) {
                case 1 :
                    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:41:6: '\\r'
                    this.match('\r'); 


                    break;

            }

            this.match('\n'); 






            this.state.type = _type;
            this.state.channel = _channel;
        }
        finally {
        }
    },
    // $ANTLR end "NL",

    mTokens: function() {
        // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:1:8: ( T__11 | Person | ID | STRING | COMMENT | WS | INT | NL )
        var alt10=8;
        alt10 = this.dfa10.predict(this.input);
        switch (alt10) {
            case 1 :
                // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:1:10: T__11
                this.mT__11(); 


                break;
            case 2 :
                // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:1:16: Person
                this.mPerson(); 


                break;
            case 3 :
                // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:1:23: ID
                this.mID(); 


                break;
            case 4 :
                // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:1:26: STRING
                this.mSTRING(); 


                break;
            case 5 :
                // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:1:33: COMMENT
                this.mCOMMENT(); 


                break;
            case 6 :
                // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:1:41: WS
                this.mWS(); 


                break;
            case 7 :
                // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:1:44: INT
                this.mINT(); 


                break;
            case 8 :
                // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:1:48: NL
                this.mNL(); 


                break;

        }

    }

}, true); // important to pass true to overwrite default implementations

org.antlr.lang.augmentObject(ExampleLexer, {
    DFA10_eotS:
        "\u0001\uffff\u0002\u0003\u0003\uffff\u0001\u0009\u0003\uffff\u0002"+
    "\u0003\u0001\uffff\u0004\u0003\u0001\u0014\u0001\u0003\u0002\uffff",
    DFA10_eofS:
        "\u0015\uffff",
    DFA10_minS:
        "\u0001\u0009\u0002\u0065\u0003\uffff\u0001\u000a\u0003\uffff\u0001"+
    "\u006c\u0001\u0066\u0001\uffff\u0001\u006c\u0001\u0041\u0001\u006f\u0001"+
    "\u000a\u0001\u0030\u0001\u000a\u0002\uffff",
    DFA10_maxS:
        "\u0001\u007a\u0002\u0065\u0003\uffff\u0001\u000a\u0003\uffff\u0001"+
    "\u006c\u0001\u0066\u0001\uffff\u0001\u006c\u0001\u007a\u0001\u006f\u0003"+
    "\u007a\u0002\uffff",
    DFA10_acceptS:
        "\u0003\uffff\u0001\u0003\u0001\u0004\u0001\u0005\u0001\uffff\u0001"+
    "\u0007\u0002\u0006\u0002\uffff\u0001\u0008\u0006\uffff\u0001\u0002\u0001"+
    "\u0001",
    DFA10_specialS:
        "\u0015\uffff}>",
    DFA10_transitionS: [
            "\u0001\u0009\u0001\u0008\u0001\uffff\u0001\u0009\u0001\u0006"+
            "\u0012\uffff\u0001\u0009\u0001\uffff\u0001\u0004\u0004\uffff"+
            "\u0001\u0004\u0007\uffff\u0001\u0005\u000a\u0007\u0007\uffff"+
            "\u0007\u0003\u0001\u0001\u0012\u0003\u0004\uffff\u0001\u0003"+
            "\u0001\uffff\u0003\u0003\u0001\u0002\u0016\u0003",
            "\u0001\u000a",
            "\u0001\u000b",
            "",
            "",
            "",
            "\u0001\u000c",
            "",
            "",
            "",
            "\u0001\u000d",
            "\u0001\u000e",
            "",
            "\u0001\u000f",
            "\u001a\u0010\u0004\uffff\u0001\u0010\u0001\uffff\u001a\u0010",
            "\u0001\u0011",
            "\u0001\u0013\u0002\uffff\u0001\u0013\u0022\uffff\u000a\u0012"+
            "\u0007\uffff\u001a\u0012\u0004\uffff\u0001\u0012\u0001\uffff"+
            "\u001a\u0012",
            "\u000a\u0003\u0007\uffff\u001a\u0003\u0004\uffff\u0001\u0003"+
            "\u0001\uffff\u001a\u0003",
            "\u0001\u0013\u0002\uffff\u0001\u0013\u0022\uffff\u000a\u0012"+
            "\u0007\uffff\u001a\u0012\u0004\uffff\u0001\u0012\u0001\uffff"+
            "\u001a\u0012",
            "",
            ""
    ]
});

org.antlr.lang.augmentObject(ExampleLexer, {
    DFA10_eot:
        org.antlr.runtime.DFA.unpackEncodedString(ExampleLexer.DFA10_eotS),
    DFA10_eof:
        org.antlr.runtime.DFA.unpackEncodedString(ExampleLexer.DFA10_eofS),
    DFA10_min:
        org.antlr.runtime.DFA.unpackEncodedStringToUnsignedChars(ExampleLexer.DFA10_minS),
    DFA10_max:
        org.antlr.runtime.DFA.unpackEncodedStringToUnsignedChars(ExampleLexer.DFA10_maxS),
    DFA10_accept:
        org.antlr.runtime.DFA.unpackEncodedString(ExampleLexer.DFA10_acceptS),
    DFA10_special:
        org.antlr.runtime.DFA.unpackEncodedString(ExampleLexer.DFA10_specialS),
    DFA10_transition: (function() {
        var a = [],
            i,
            numStates = ExampleLexer.DFA10_transitionS.length;
        for (i=0; i<numStates; i++) {
            a.push(org.antlr.runtime.DFA.unpackEncodedString(ExampleLexer.DFA10_transitionS[i]));
        }
        return a;
    })()
});

ExampleLexer.DFA10 = function(recognizer) {
    this.recognizer = recognizer;
    this.decisionNumber = 10;
    this.eot = ExampleLexer.DFA10_eot;
    this.eof = ExampleLexer.DFA10_eof;
    this.min = ExampleLexer.DFA10_min;
    this.max = ExampleLexer.DFA10_max;
    this.accept = ExampleLexer.DFA10_accept;
    this.special = ExampleLexer.DFA10_special;
    this.transition = ExampleLexer.DFA10_transition;
};

org.antlr.lang.extend(ExampleLexer.DFA10, org.antlr.runtime.DFA, {
    getDescription: function() {
        return "1:1: Tokens : ( T__11 | Person | ID | STRING | COMMENT | WS | INT | NL );";
    },
    dummy: null
});
 
})();