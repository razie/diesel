// $ANTLR 3.3 avr. 19, 2016 01:13:22 /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g 2016-06-17 12:48:05



var Nvp1Parser = function(input, state) {
    if (!state) {
        state = new org.antlr.runtime.RecognizerSharedState();
    }

    (function(){
    }).call(this);

    Nvp1Parser.superclass.constructor.call(this, input, state);


         

    /* @todo only create adaptor if output=AST */
    this.adaptor = new org.antlr.runtime.tree.CommonTreeAdaptor();

};

org.antlr.lang.augmentObject(Nvp1Parser, {
    EOF: -1,
    T__9: 9,
    T__10: 10,
    T__11: 11,
    T__12: 12,
    T__13: 13,
    T__14: 14,
    T__15: 15,
    T__16: 16,
    T__17: 17,
    T__18: 18,
    T__19: 19,
    T__20: 20,
    T__21: 21,
    T__22: 22,
    T__23: 23,
    T__24: 24,
    T__25: 25,
    T__26: 26,
    T__27: 27,
    T__28: 28,
    T__29: 29,
    T__30: 30,
    T__31: 31,
    T__32: 32,
    T__33: 33,
    T__34: 34,
    T__35: 35,
    T__36: 36,
    ID: 4,
    STRING: 5,
    INT: 6,
    COMMENT: 7,
    WS: 8
});

(function(){
// public class variables
var EOF= -1,
    T__9= 9,
    T__10= 10,
    T__11= 11,
    T__12= 12,
    T__13= 13,
    T__14= 14,
    T__15= 15,
    T__16= 16,
    T__17= 17,
    T__18= 18,
    T__19= 19,
    T__20= 20,
    T__21= 21,
    T__22= 22,
    T__23= 23,
    T__24= 24,
    T__25= 25,
    T__26= 26,
    T__27= 27,
    T__28= 28,
    T__29= 29,
    T__30= 30,
    T__31= 31,
    T__32= 32,
    T__33= 33,
    T__34= 34,
    T__35= 35,
    T__36= 36,
    ID= 4,
    STRING= 5,
    INT= 6,
    COMMENT= 7,
    WS= 8;

// public instance methods/vars
org.antlr.lang.extend(Nvp1Parser, org.antlr.runtime.Parser, {
        
    setTreeAdaptor: function(adaptor) {
        this.adaptor = adaptor;
    },
    getTreeAdaptor: function() {
        return this.adaptor;
    },

    getTokenNames: function() { return Nvp1Parser.tokenNames; },
    getGrammarFileName: function() { return "/Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g"; }
});
org.antlr.lang.augmentObject(Nvp1Parser.prototype, {

    // inline static return class
    rule_DomainModel_return: (function() {
        Nvp1Parser.rule_DomainModel_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_DomainModel_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:22:1: rule_DomainModel : (elements+= rule_AbstractElement )* EOF ;
    // $ANTLR start "rule_DomainModel"
    rule_DomainModel: function() {
        var retval = new Nvp1Parser.rule_DomainModel_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var EOF1 = null;
        var list_elements=null;
        var elements = null;
        var EOF1_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:22:17: ( (elements+= rule_AbstractElement )* EOF )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:23:5: (elements+= rule_AbstractElement )* EOF
            root_0 = this.adaptor.nil();

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:23:13: (elements+= rule_AbstractElement )*
            loop1:
            do {
                var alt1=2;
                var LA1_0 = this.input.LA(1);

                if ( (LA1_0==9||LA1_0==12||(LA1_0>=14 && LA1_0<=16)||LA1_0==19||(LA1_0>=21 && LA1_0<=22)) ) {
                    alt1=1;
                }


                switch (alt1) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:23:13: elements+= rule_AbstractElement
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AbstractElement_in_rule_DomainModel67);
                    elements=this.rule_AbstractElement();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, elements.getTree());
                    if (org.antlr.lang.isNull(list_elements)) list_elements = [];
                    list_elements.push(elements.getTree());



                    break;

                default :
                    break loop1;
                }
            } while (true);

            EOF1=this.match(this.input,EOF,Nvp1Parser.FOLLOW_EOF_in_rule_DomainModel70); 



            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_AbstractElement_return: (function() {
        Nvp1Parser.rule_AbstractElement_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_AbstractElement_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:28:1: rule_AbstractElement : ( rule_Expect | rule_Msg | rule_When | rule_Option | rule_Val | rule_Mock | rule_Topic | rule_Braq );
    // $ANTLR start "rule_AbstractElement"
    rule_AbstractElement: function() {
        var retval = new Nvp1Parser.rule_AbstractElement_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

         var rule_Expect2 = null;
         var rule_Msg3 = null;
         var rule_When4 = null;
         var rule_Option5 = null;
         var rule_Val6 = null;
         var rule_Mock7 = null;
         var rule_Topic8 = null;
         var rule_Braq9 = null;


        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:28:21: ( rule_Expect | rule_Msg | rule_When | rule_Option | rule_Val | rule_Mock | rule_Topic | rule_Braq )
            var alt2=8;
            switch ( this.input.LA(1) ) {
            case 14:
                alt2=1;
                break;
            case 15:
                alt2=2;
                break;
            case 19:
                alt2=3;
                break;
            case 22:
                alt2=4;
                break;
            case 16:
                alt2=5;
                break;
            case 21:
                alt2=6;
                break;
            case 9:
                alt2=7;
                break;
            case 12:
                alt2=8;
                break;
            default:
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 2, 0, this.input);

                throw nvae;
            }

            switch (alt2) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:5: rule_Expect
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Expect_in_rule_AbstractElement85);
                    rule_Expect2=this.rule_Expect();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Expect2.getTree());


                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:19: rule_Msg
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Msg_in_rule_AbstractElement89);
                    rule_Msg3=this.rule_Msg();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Msg3.getTree());


                    break;
                case 3 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:30: rule_When
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_When_in_rule_AbstractElement93);
                    rule_When4=this.rule_When();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_When4.getTree());


                    break;
                case 4 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:42: rule_Option
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Option_in_rule_AbstractElement97);
                    rule_Option5=this.rule_Option();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Option5.getTree());


                    break;
                case 5 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:56: rule_Val
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Val_in_rule_AbstractElement101);
                    rule_Val6=this.rule_Val();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Val6.getTree());


                    break;
                case 6 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:67: rule_Mock
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Mock_in_rule_AbstractElement105);
                    rule_Mock7=this.rule_Mock();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Mock7.getTree());


                    break;
                case 7 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:79: rule_Topic
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Topic_in_rule_AbstractElement109);
                    rule_Topic8=this.rule_Topic();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Topic8.getTree());


                    break;
                case 8 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:92: rule_Braq
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Braq_in_rule_AbstractElement113);
                    rule_Braq9=this.rule_Braq();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Braq9.getTree());


                    break;

            }
            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_Topic_return: (function() {
        Nvp1Parser.rule_Topic_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Topic_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:36:1: rule_Topic : '[[' name= rule_QualifiedName ( ':' t= rule_QualifiedName )? ']]' ;
    // $ANTLR start "rule_Topic"
    rule_Topic: function() {
        var retval = new Nvp1Parser.rule_Topic_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal10 = null;
        var char_literal11 = null;
        var string_literal12 = null;
         var name = null;
         var t = null;

        var string_literal10_tree=null;
        var char_literal11_tree=null;
        var string_literal12_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:36:11: ( '[[' name= rule_QualifiedName ( ':' t= rule_QualifiedName )? ']]' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:5: '[[' name= rule_QualifiedName ( ':' t= rule_QualifiedName )? ']]'
            root_0 = this.adaptor.nil();

            string_literal10=this.match(this.input,9,Nvp1Parser.FOLLOW_9_in_rule_Topic129); 
            string_literal10_tree = this.adaptor.create(string_literal10);
            this.adaptor.addChild(root_0, string_literal10_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_Topic133);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:34: ( ':' t= rule_QualifiedName )?
            var alt3=2;
            var LA3_0 = this.input.LA(1);

            if ( (LA3_0==10) ) {
                alt3=1;
            }
            switch (alt3) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:35: ':' t= rule_QualifiedName
                    char_literal11=this.match(this.input,10,Nvp1Parser.FOLLOW_10_in_rule_Topic136); 
                    char_literal11_tree = this.adaptor.create(char_literal11);
                    this.adaptor.addChild(root_0, char_literal11_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_Topic140);
                    t=this.rule_QualifiedName();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, t.getTree());


                    break;

            }

            string_literal12=this.match(this.input,11,Nvp1Parser.FOLLOW_11_in_rule_Topic144); 
            string_literal12_tree = this.adaptor.create(string_literal12);
            this.adaptor.addChild(root_0, string_literal12_tree);




            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_Braq_return: (function() {
        Nvp1Parser.rule_Braq_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Braq_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:43:1: rule_Braq : '}' ;
    // $ANTLR start "rule_Braq"
    rule_Braq: function() {
        var retval = new Nvp1Parser.rule_Braq_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal13 = null;

        var char_literal13_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:43:10: ( '}' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:44:5: '}'
            root_0 = this.adaptor.nil();

            char_literal13=this.match(this.input,12,Nvp1Parser.FOLLOW_12_in_rule_Braq159); 
            char_literal13_tree = this.adaptor.create(char_literal13);
            this.adaptor.addChild(root_0, char_literal13_tree);




            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_Expect_return: (function() {
        Nvp1Parser.rule_Expect_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Expect_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:50:1: rule_Expect : ( rule_ExpectM | rule_ExpectV );
    // $ANTLR start "rule_Expect"
    rule_Expect: function() {
        var retval = new Nvp1Parser.rule_Expect_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

         var rule_ExpectM14 = null;
         var rule_ExpectV15 = null;


        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:50:12: ( rule_ExpectM | rule_ExpectV )
            var alt4=2;
            var LA4_0 = this.input.LA(1);

            if ( (LA4_0==14) ) {
                var LA4_1 = this.input.LA(2);

                if ( (LA4_1==15) ) {
                    alt4=1;
                }
                else if ( (LA4_1==16) ) {
                    alt4=2;
                }
                else {
                    var nvae =
                        new org.antlr.runtime.NoViableAltException("", 4, 1, this.input);

                    throw nvae;
                }
            }
            else {
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 4, 0, this.input);

                throw nvae;
            }
            switch (alt4) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:5: rule_ExpectM
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_ExpectM_in_rule_Expect174);
                    rule_ExpectM14=this.rule_ExpectM();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_ExpectM14.getTree());


                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:20: rule_ExpectV
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_ExpectV_in_rule_Expect178);
                    rule_ExpectV15=this.rule_ExpectV();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_ExpectV15.getTree());


                    break;

            }
            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_Condition_return: (function() {
        Nvp1Parser.rule_Condition_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Condition_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:57:1: rule_Condition : '$if' attrs= rule_AttrSpecs ;
    // $ANTLR start "rule_Condition"
    rule_Condition: function() {
        var retval = new Nvp1Parser.rule_Condition_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal16 = null;
         var attrs = null;

        var string_literal16_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:57:15: ( '$if' attrs= rule_AttrSpecs )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:58:5: '$if' attrs= rule_AttrSpecs
            root_0 = this.adaptor.nil();

            string_literal16=this.match(this.input,13,Nvp1Parser.FOLLOW_13_in_rule_Condition193); 
            string_literal16_tree = this.adaptor.create(string_literal16);
            this.adaptor.addChild(root_0, string_literal16_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Condition197);
            attrs=this.rule_AttrSpecs();

            this.state._fsp--;

            this.adaptor.addChild(root_0, attrs.getTree());



            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_ExpectM_return: (function() {
        Nvp1Parser.rule_ExpectM_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_ExpectM_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:64:1: rule_ExpectM : '$expect' ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? ) (cond= rule_Condition )? ;
    // $ANTLR start "rule_ExpectM"
    rule_ExpectM: function() {
        var retval = new Nvp1Parser.rule_ExpectM_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal17 = null;
        var string_literal18 = null;
         var name = null;
         var attrs = null;
         var cond = null;

        var string_literal17_tree=null;
        var string_literal18_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:64:13: ( '$expect' ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? ) (cond= rule_Condition )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:5: '$expect' ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? ) (cond= rule_Condition )?
            root_0 = this.adaptor.nil();

            string_literal17=this.match(this.input,14,Nvp1Parser.FOLLOW_14_in_rule_ExpectM212); 
            string_literal17_tree = this.adaptor.create(string_literal17);
            this.adaptor.addChild(root_0, string_literal17_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:15: ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:16: '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )?
            string_literal18=this.match(this.input,15,Nvp1Parser.FOLLOW_15_in_rule_ExpectM215); 
            string_literal18_tree = this.adaptor.create(string_literal18);
            this.adaptor.addChild(root_0, string_literal18_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_ExpectM219);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:52: (attrs= rule_AttrSpecs )?
            var alt5=2;
            var LA5_0 = this.input.LA(1);

            if ( (LA5_0==24) ) {
                alt5=1;
            }
            switch (alt5) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:52: attrs= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_ExpectM223);
                    attrs=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());


                    break;

            }




            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:70: (cond= rule_Condition )?
            var alt6=2;
            var LA6_0 = this.input.LA(1);

            if ( (LA6_0==13) ) {
                alt6=1;
            }
            switch (alt6) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:71: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_ExpectM230);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }




            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_ExpectV_return: (function() {
        Nvp1Parser.rule_ExpectV_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_ExpectV_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:70:1: rule_ExpectV : '$expect' ( '$val' (p= rule_AttrSpec )? ) (cond= rule_Condition )? ;
    // $ANTLR start "rule_ExpectV"
    rule_ExpectV: function() {
        var retval = new Nvp1Parser.rule_ExpectV_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal19 = null;
        var string_literal20 = null;
         var p = null;
         var cond = null;

        var string_literal19_tree=null;
        var string_literal20_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:70:13: ( '$expect' ( '$val' (p= rule_AttrSpec )? ) (cond= rule_Condition )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:71:5: '$expect' ( '$val' (p= rule_AttrSpec )? ) (cond= rule_Condition )?
            root_0 = this.adaptor.nil();

            string_literal19=this.match(this.input,14,Nvp1Parser.FOLLOW_14_in_rule_ExpectV246); 
            string_literal19_tree = this.adaptor.create(string_literal19);
            this.adaptor.addChild(root_0, string_literal19_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:71:15: ( '$val' (p= rule_AttrSpec )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:71:16: '$val' (p= rule_AttrSpec )?
            string_literal20=this.match(this.input,16,Nvp1Parser.FOLLOW_16_in_rule_ExpectV249); 
            string_literal20_tree = this.adaptor.create(string_literal20);
            this.adaptor.addChild(root_0, string_literal20_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:71:24: (p= rule_AttrSpec )?
            var alt7=2;
            var LA7_0 = this.input.LA(1);

            if ( (LA7_0==ID) ) {
                alt7=1;
            }
            switch (alt7) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:71:24: p= rule_AttrSpec
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_ExpectV253);
                    p=this.rule_AttrSpec();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, p.getTree());


                    break;

            }




            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:71:41: (cond= rule_Condition )?
            var alt8=2;
            var LA8_0 = this.input.LA(1);

            if ( (LA8_0==13) ) {
                alt8=1;
            }
            switch (alt8) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:71:42: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_ExpectV260);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }




            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_Val_return: (function() {
        Nvp1Parser.rule_Val_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Val_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:76:1: rule_Val : '$val' p= rule_AttrSpec ;
    // $ANTLR start "rule_Val"
    rule_Val: function() {
        var retval = new Nvp1Parser.rule_Val_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal21 = null;
         var p = null;

        var string_literal21_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:76:9: ( '$val' p= rule_AttrSpec )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:77:5: '$val' p= rule_AttrSpec
            root_0 = this.adaptor.nil();

            string_literal21=this.match(this.input,16,Nvp1Parser.FOLLOW_16_in_rule_Val276); 
            string_literal21_tree = this.adaptor.create(string_literal21);
            this.adaptor.addChild(root_0, string_literal21_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_Val280);
            p=this.rule_AttrSpec();

            this.state._fsp--;

            this.adaptor.addChild(root_0, p.getTree());



            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_Msg_return: (function() {
        Nvp1Parser.rule_Msg_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Msg_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:83:1: rule_Msg : '$msg' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? ;
    // $ANTLR start "rule_Msg"
    rule_Msg: function() {
        var retval = new Nvp1Parser.rule_Msg_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal22 = null;
        var char_literal23 = null;
        var char_literal24 = null;
         var stype = null;
         var name = null;
         var attrs = null;

        var string_literal22_tree=null;
        var char_literal23_tree=null;
        var char_literal24_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:83:9: ( '$msg' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:84:5: '$msg' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )?
            root_0 = this.adaptor.nil();

            string_literal22=this.match(this.input,15,Nvp1Parser.FOLLOW_15_in_rule_Msg295); 
            string_literal22_tree = this.adaptor.create(string_literal22);
            this.adaptor.addChild(root_0, string_literal22_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:84:12: ( '<' stype= rule_MsgStereo '>' )?
            var alt9=2;
            var LA9_0 = this.input.LA(1);

            if ( (LA9_0==17) ) {
                alt9=1;
            }
            switch (alt9) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:84:13: '<' stype= rule_MsgStereo '>'
                    char_literal23=this.match(this.input,17,Nvp1Parser.FOLLOW_17_in_rule_Msg298); 
                    char_literal23_tree = this.adaptor.create(char_literal23);
                    this.adaptor.addChild(root_0, char_literal23_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_MsgStereo_in_rule_Msg302);
                    stype=this.rule_MsgStereo();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, stype.getTree());
                    char_literal24=this.match(this.input,18,Nvp1Parser.FOLLOW_18_in_rule_Msg304); 
                    char_literal24_tree = this.adaptor.create(char_literal24);
                    this.adaptor.addChild(root_0, char_literal24_tree);



                    break;

            }

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_Msg310);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:84:73: (attrs= rule_AttrSpecs )?
            var alt10=2;
            var LA10_0 = this.input.LA(1);

            if ( (LA10_0==24) ) {
                alt10=1;
            }
            switch (alt10) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:84:73: attrs= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Msg314);
                    attrs=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());


                    break;

            }




            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_When_return: (function() {
        Nvp1Parser.rule_When_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_When_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:90:1: rule_When : '$when' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' z= ID (za= rule_AttrSpecs )? ;
    // $ANTLR start "rule_When"
    rule_When: function() {
        var retval = new Nvp1Parser.rule_When_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var a = null;
        var z = null;
        var string_literal25 = null;
        var string_literal26 = null;
         var aa = null;
         var cond = null;
         var za = null;

        var a_tree=null;
        var z_tree=null;
        var string_literal25_tree=null;
        var string_literal26_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:90:10: ( '$when' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' z= ID (za= rule_AttrSpecs )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:91:2: '$when' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' z= ID (za= rule_AttrSpecs )?
            root_0 = this.adaptor.nil();

            string_literal25=this.match(this.input,19,Nvp1Parser.FOLLOW_19_in_rule_When327); 
            string_literal25_tree = this.adaptor.create(string_literal25);
            this.adaptor.addChild(root_0, string_literal25_tree);

            a=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_When331); 
            a_tree = this.adaptor.create(a);
            this.adaptor.addChild(root_0, a_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:91:17: (aa= rule_Attrs )?
            var alt11=2;
            var LA11_0 = this.input.LA(1);

            if ( (LA11_0==24) ) {
                alt11=1;
            }
            switch (alt11) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:91:17: aa= rule_Attrs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attrs_in_rule_When335);
                    aa=this.rule_Attrs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, aa.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:91:34: (cond= rule_Condition )?
            var alt12=2;
            var LA12_0 = this.input.LA(1);

            if ( (LA12_0==13) ) {
                alt12=1;
            }
            switch (alt12) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:91:34: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_When340);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            string_literal26=this.match(this.input,20,Nvp1Parser.FOLLOW_20_in_rule_When343); 
            string_literal26_tree = this.adaptor.create(string_literal26);
            this.adaptor.addChild(root_0, string_literal26_tree);

            z=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_When347); 
            z_tree = this.adaptor.create(z);
            this.adaptor.addChild(root_0, z_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:91:63: (za= rule_AttrSpecs )?
            var alt13=2;
            var LA13_0 = this.input.LA(1);

            if ( (LA13_0==24) ) {
                alt13=1;
            }
            switch (alt13) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:91:63: za= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_When351);
                    za=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, za.getTree());


                    break;

            }




            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_Mock_return: (function() {
        Nvp1Parser.rule_Mock_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Mock_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:97:1: rule_Mock : '$mock' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' (za= rule_AttrSpecs )? ;
    // $ANTLR start "rule_Mock"
    rule_Mock: function() {
        var retval = new Nvp1Parser.rule_Mock_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var a = null;
        var string_literal27 = null;
        var string_literal28 = null;
         var aa = null;
         var cond = null;
         var za = null;

        var a_tree=null;
        var string_literal27_tree=null;
        var string_literal28_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:97:10: ( '$mock' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' (za= rule_AttrSpecs )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:98:2: '$mock' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' (za= rule_AttrSpecs )?
            root_0 = this.adaptor.nil();

            string_literal27=this.match(this.input,21,Nvp1Parser.FOLLOW_21_in_rule_Mock365); 
            string_literal27_tree = this.adaptor.create(string_literal27);
            this.adaptor.addChild(root_0, string_literal27_tree);

            a=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_Mock369); 
            a_tree = this.adaptor.create(a);
            this.adaptor.addChild(root_0, a_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:98:17: (aa= rule_Attrs )?
            var alt14=2;
            var LA14_0 = this.input.LA(1);

            if ( (LA14_0==24) ) {
                alt14=1;
            }
            switch (alt14) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:98:17: aa= rule_Attrs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attrs_in_rule_Mock373);
                    aa=this.rule_Attrs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, aa.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:98:34: (cond= rule_Condition )?
            var alt15=2;
            var LA15_0 = this.input.LA(1);

            if ( (LA15_0==13) ) {
                alt15=1;
            }
            switch (alt15) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:98:34: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_Mock378);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            string_literal28=this.match(this.input,20,Nvp1Parser.FOLLOW_20_in_rule_Mock381); 
            string_literal28_tree = this.adaptor.create(string_literal28);
            this.adaptor.addChild(root_0, string_literal28_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:98:58: (za= rule_AttrSpecs )?
            var alt16=2;
            var LA16_0 = this.input.LA(1);

            if ( (LA16_0==24) ) {
                alt16=1;
            }
            switch (alt16) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:98:58: za= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Mock385);
                    za=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, za.getTree());


                    break;

            }




            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_Option_return: (function() {
        Nvp1Parser.rule_Option_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Option_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:104:1: rule_Option : '$opt' attr= rule_AttrSpec ;
    // $ANTLR start "rule_Option"
    rule_Option: function() {
        var retval = new Nvp1Parser.rule_Option_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal29 = null;
         var attr = null;

        var string_literal29_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:104:12: ( '$opt' attr= rule_AttrSpec )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:105:5: '$opt' attr= rule_AttrSpec
            root_0 = this.adaptor.nil();

            string_literal29=this.match(this.input,22,Nvp1Parser.FOLLOW_22_in_rule_Option401); 
            string_literal29_tree = this.adaptor.create(string_literal29);
            this.adaptor.addChild(root_0, string_literal29_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_Option405);
            attr=this.rule_AttrSpec();

            this.state._fsp--;

            this.adaptor.addChild(root_0, attr.getTree());



            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_AttrSpec_return: (function() {
        Nvp1Parser.rule_AttrSpec_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_AttrSpec_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:111:1: rule_AttrSpec : name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? ;
    // $ANTLR start "rule_AttrSpec"
    rule_AttrSpec: function() {
        var retval = new Nvp1Parser.rule_AttrSpec_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var name = null;
        var char_literal30 = null;
        var char_literal31 = null;
         var ttype = null;
         var eexpr = null;

        var name_tree=null;
        var char_literal30_tree=null;
        var char_literal31_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:111:14: (name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:112:3: name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )?
            root_0 = this.adaptor.nil();

            name=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_AttrSpec420); 
            name_tree = this.adaptor.create(name);
            this.adaptor.addChild(root_0, name_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:112:11: ( ':' ttype= rule_DataType )?
            var alt17=2;
            var LA17_0 = this.input.LA(1);

            if ( (LA17_0==10) ) {
                alt17=1;
            }
            switch (alt17) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:112:12: ':' ttype= rule_DataType
                    char_literal30=this.match(this.input,10,Nvp1Parser.FOLLOW_10_in_rule_AttrSpec423); 
                    char_literal30_tree = this.adaptor.create(char_literal30);
                    this.adaptor.addChild(root_0, char_literal30_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_DataType_in_rule_AttrSpec427);
                    ttype=this.rule_DataType();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, ttype.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:112:38: ( '=' eexpr= rule_EXPR )?
            var alt18=2;
            var LA18_0 = this.input.LA(1);

            if ( (LA18_0==23) ) {
                alt18=1;
            }
            switch (alt18) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:112:39: '=' eexpr= rule_EXPR
                    char_literal31=this.match(this.input,23,Nvp1Parser.FOLLOW_23_in_rule_AttrSpec432); 
                    char_literal31_tree = this.adaptor.create(char_literal31);
                    this.adaptor.addChild(root_0, char_literal31_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_EXPR_in_rule_AttrSpec436);
                    eexpr=this.rule_EXPR();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, eexpr.getTree());


                    break;

            }




            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_Attr_return: (function() {
        Nvp1Parser.rule_Attr_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Attr_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:117:1: rule_Attr : name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? ;
    // $ANTLR start "rule_Attr"
    rule_Attr: function() {
        var retval = new Nvp1Parser.rule_Attr_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var name = null;
        var char_literal32 = null;
        var char_literal33 = null;
         var ttype = null;
         var eexpr = null;

        var name_tree=null;
        var char_literal32_tree=null;
        var char_literal33_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:117:10: (name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:118:3: name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )?
            root_0 = this.adaptor.nil();

            name=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_Attr452); 
            name_tree = this.adaptor.create(name);
            this.adaptor.addChild(root_0, name_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:118:11: ( ':' ttype= rule_DataType )?
            var alt19=2;
            var LA19_0 = this.input.LA(1);

            if ( (LA19_0==10) ) {
                alt19=1;
            }
            switch (alt19) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:118:12: ':' ttype= rule_DataType
                    char_literal32=this.match(this.input,10,Nvp1Parser.FOLLOW_10_in_rule_Attr455); 
                    char_literal32_tree = this.adaptor.create(char_literal32);
                    this.adaptor.addChild(root_0, char_literal32_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_DataType_in_rule_Attr459);
                    ttype=this.rule_DataType();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, ttype.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:118:38: ( '=' eexpr= rule_EXPR )?
            var alt20=2;
            var LA20_0 = this.input.LA(1);

            if ( (LA20_0==23) ) {
                alt20=1;
            }
            switch (alt20) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:118:39: '=' eexpr= rule_EXPR
                    char_literal33=this.match(this.input,23,Nvp1Parser.FOLLOW_23_in_rule_Attr464); 
                    char_literal33_tree = this.adaptor.create(char_literal33);
                    this.adaptor.addChild(root_0, char_literal33_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_EXPR_in_rule_Attr468);
                    eexpr=this.rule_EXPR();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, eexpr.getTree());


                    break;

            }




            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_EXPR_return: (function() {
        Nvp1Parser.rule_EXPR_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_EXPR_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:1: rule_EXPR : (parm= ID | svalue= STRING | ivalue= INT );
    // $ANTLR start "rule_EXPR"
    rule_EXPR: function() {
        var retval = new Nvp1Parser.rule_EXPR_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var parm = null;
        var svalue = null;
        var ivalue = null;

        var parm_tree=null;
        var svalue_tree=null;
        var ivalue_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:10: (parm= ID | svalue= STRING | ivalue= INT )
            var alt21=3;
            switch ( this.input.LA(1) ) {
            case ID:
                alt21=1;
                break;
            case STRING:
                alt21=2;
                break;
            case INT:
                alt21=3;
                break;
            default:
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 21, 0, this.input);

                throw nvae;
            }

            switch (alt21) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:124:3: parm= ID
                    root_0 = this.adaptor.nil();

                    parm=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_EXPR484); 
                    parm_tree = this.adaptor.create(parm);
                    this.adaptor.addChild(root_0, parm_tree);



                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:124:13: svalue= STRING
                    root_0 = this.adaptor.nil();

                    svalue=this.match(this.input,STRING,Nvp1Parser.FOLLOW_STRING_in_rule_EXPR490); 
                    svalue_tree = this.adaptor.create(svalue);
                    this.adaptor.addChild(root_0, svalue_tree);



                    break;
                case 3 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:124:29: ivalue= INT
                    root_0 = this.adaptor.nil();

                    ivalue=this.match(this.input,INT,Nvp1Parser.FOLLOW_INT_in_rule_EXPR496); 
                    ivalue_tree = this.adaptor.create(ivalue);
                    this.adaptor.addChild(root_0, ivalue_tree);



                    break;

            }
            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_Attrs_return: (function() {
        Nvp1Parser.rule_Attrs_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Attrs_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:129:1: rule_Attrs : ( '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')' ) ;
    // $ANTLR start "rule_Attrs"
    rule_Attrs: function() {
        var retval = new Nvp1Parser.rule_Attrs_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal34 = null;
        var char_literal35 = null;
        var char_literal36 = null;
        var list_attrs=null;
        var attrs = null;
        var char_literal34_tree=null;
        var char_literal35_tree=null;
        var char_literal36_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:129:11: ( ( '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')' ) )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:130:5: ( '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')' )
            root_0 = this.adaptor.nil();

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:130:5: ( '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:130:6: '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')'
            char_literal34=this.match(this.input,24,Nvp1Parser.FOLLOW_24_in_rule_Attrs513); 
            char_literal34_tree = this.adaptor.create(char_literal34);
            this.adaptor.addChild(root_0, char_literal34_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:130:10: (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )?
            var alt23=2;
            var LA23_0 = this.input.LA(1);

            if ( (LA23_0==ID) ) {
                alt23=1;
            }
            switch (alt23) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:130:11: attrs+= rule_Attr ( ',' attrs+= rule_Attr )*
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attr_in_rule_Attrs518);
                    attrs=this.rule_Attr();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());
                    if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                    list_attrs.push(attrs.getTree());

                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:130:28: ( ',' attrs+= rule_Attr )*
                    loop22:
                    do {
                        var alt22=2;
                        var LA22_0 = this.input.LA(1);

                        if ( (LA22_0==25) ) {
                            alt22=1;
                        }


                        switch (alt22) {
                        case 1 :
                            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:130:29: ',' attrs+= rule_Attr
                            char_literal35=this.match(this.input,25,Nvp1Parser.FOLLOW_25_in_rule_Attrs521); 
                            char_literal35_tree = this.adaptor.create(char_literal35);
                            this.adaptor.addChild(root_0, char_literal35_tree);

                            this.pushFollow(Nvp1Parser.FOLLOW_rule_Attr_in_rule_Attrs525);
                            attrs=this.rule_Attr();

                            this.state._fsp--;

                            this.adaptor.addChild(root_0, attrs.getTree());
                            if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                            list_attrs.push(attrs.getTree());



                            break;

                        default :
                            break loop22;
                        }
                    } while (true);



                    break;

            }

            char_literal36=this.match(this.input,26,Nvp1Parser.FOLLOW_26_in_rule_Attrs531); 
            char_literal36_tree = this.adaptor.create(char_literal36);
            this.adaptor.addChild(root_0, char_literal36_tree);







            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_AttrSpecs_return: (function() {
        Nvp1Parser.rule_AttrSpecs_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_AttrSpecs_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:136:1: rule_AttrSpecs : ( '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')' ) ;
    // $ANTLR start "rule_AttrSpecs"
    rule_AttrSpecs: function() {
        var retval = new Nvp1Parser.rule_AttrSpecs_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal37 = null;
        var char_literal38 = null;
        var char_literal39 = null;
        var list_attrs=null;
        var attrs = null;
        var char_literal37_tree=null;
        var char_literal38_tree=null;
        var char_literal39_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:136:15: ( ( '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')' ) )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:137:5: ( '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')' )
            root_0 = this.adaptor.nil();

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:137:5: ( '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:137:6: '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')'
            char_literal37=this.match(this.input,24,Nvp1Parser.FOLLOW_24_in_rule_AttrSpecs548); 
            char_literal37_tree = this.adaptor.create(char_literal37);
            this.adaptor.addChild(root_0, char_literal37_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:137:10: (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )?
            var alt25=2;
            var LA25_0 = this.input.LA(1);

            if ( (LA25_0==ID) ) {
                alt25=1;
            }
            switch (alt25) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:137:11: attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )*
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_AttrSpecs553);
                    attrs=this.rule_AttrSpec();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());
                    if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                    list_attrs.push(attrs.getTree());

                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:137:32: ( ',' attrs+= rule_AttrSpec )*
                    loop24:
                    do {
                        var alt24=2;
                        var LA24_0 = this.input.LA(1);

                        if ( (LA24_0==25) ) {
                            alt24=1;
                        }


                        switch (alt24) {
                        case 1 :
                            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:137:33: ',' attrs+= rule_AttrSpec
                            char_literal38=this.match(this.input,25,Nvp1Parser.FOLLOW_25_in_rule_AttrSpecs556); 
                            char_literal38_tree = this.adaptor.create(char_literal38);
                            this.adaptor.addChild(root_0, char_literal38_tree);

                            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_AttrSpecs560);
                            attrs=this.rule_AttrSpec();

                            this.state._fsp--;

                            this.adaptor.addChild(root_0, attrs.getTree());
                            if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                            list_attrs.push(attrs.getTree());



                            break;

                        default :
                            break loop24;
                        }
                    } while (true);



                    break;

            }

            char_literal39=this.match(this.input,26,Nvp1Parser.FOLLOW_26_in_rule_AttrSpecs566); 
            char_literal39_tree = this.adaptor.create(char_literal39);
            this.adaptor.addChild(root_0, char_literal39_tree);







            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_QualifiedNameWithWildCard_return: (function() {
        Nvp1Parser.rule_QualifiedNameWithWildCard_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_QualifiedNameWithWildCard_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:146:1: rule_QualifiedNameWithWildCard : rule_QualifiedName ( '.*' )? ;
    // $ANTLR start "rule_QualifiedNameWithWildCard"
    rule_QualifiedNameWithWildCard: function() {
        var retval = new Nvp1Parser.rule_QualifiedNameWithWildCard_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal41 = null;
         var rule_QualifiedName40 = null;

        var string_literal41_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:146:31: ( rule_QualifiedName ( '.*' )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:147:5: rule_QualifiedName ( '.*' )?
            root_0 = this.adaptor.nil();

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_QualifiedNameWithWildCard585);
            rule_QualifiedName40=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, rule_QualifiedName40.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:147:24: ( '.*' )?
            var alt26=2;
            var LA26_0 = this.input.LA(1);

            if ( (LA26_0==27) ) {
                alt26=1;
            }
            switch (alt26) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:147:24: '.*'
                    string_literal41=this.match(this.input,27,Nvp1Parser.FOLLOW_27_in_rule_QualifiedNameWithWildCard587); 
                    string_literal41_tree = this.adaptor.create(string_literal41);
                    this.adaptor.addChild(root_0, string_literal41_tree);



                    break;

            }




            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_QualifiedName_return: (function() {
        Nvp1Parser.rule_QualifiedName_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_QualifiedName_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:152:1: rule_QualifiedName : ID ( '.' ID )* ;
    // $ANTLR start "rule_QualifiedName"
    rule_QualifiedName: function() {
        var retval = new Nvp1Parser.rule_QualifiedName_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var ID42 = null;
        var char_literal43 = null;
        var ID44 = null;

        var ID42_tree=null;
        var char_literal43_tree=null;
        var ID44_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:152:19: ( ID ( '.' ID )* )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:153:5: ID ( '.' ID )*
            root_0 = this.adaptor.nil();

            ID42=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_QualifiedName602); 
            ID42_tree = this.adaptor.create(ID42);
            this.adaptor.addChild(root_0, ID42_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:153:8: ( '.' ID )*
            loop27:
            do {
                var alt27=2;
                var LA27_0 = this.input.LA(1);

                if ( (LA27_0==28) ) {
                    alt27=1;
                }


                switch (alt27) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:153:9: '.' ID
                    char_literal43=this.match(this.input,28,Nvp1Parser.FOLLOW_28_in_rule_QualifiedName605); 
                    char_literal43_tree = this.adaptor.create(char_literal43);
                    this.adaptor.addChild(root_0, char_literal43_tree);

                    ID44=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_QualifiedName607); 
                    ID44_tree = this.adaptor.create(ID44);
                    this.adaptor.addChild(root_0, ID44_tree);



                    break;

                default :
                    break loop27;
                }
            } while (true);




            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_DataType_return: (function() {
        Nvp1Parser.rule_DataType_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_DataType_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:156:1: rule_DataType : (string= 'String' | int= 'Int' | date= 'Date' );
    // $ANTLR start "rule_DataType"
    rule_DataType: function() {
        var retval = new Nvp1Parser.rule_DataType_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string = null;
        var int = null;
        var date = null;

        var string_tree=null;
        var int_tree=null;
        var date_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:156:14: (string= 'String' | int= 'Int' | date= 'Date' )
            var alt28=3;
            switch ( this.input.LA(1) ) {
            case 29:
                alt28=1;
                break;
            case 30:
                alt28=2;
                break;
            case 31:
                alt28=3;
                break;
            default:
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 28, 0, this.input);

                throw nvae;
            }

            switch (alt28) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:157:2: string= 'String'
                    root_0 = this.adaptor.nil();

                    string=this.match(this.input,29,Nvp1Parser.FOLLOW_29_in_rule_DataType620); 
                    string_tree = this.adaptor.create(string);
                    this.adaptor.addChild(root_0, string_tree);



                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:157:20: int= 'Int'
                    root_0 = this.adaptor.nil();

                    int=this.match(this.input,30,Nvp1Parser.FOLLOW_30_in_rule_DataType626); 
                    int_tree = this.adaptor.create(int);
                    this.adaptor.addChild(root_0, int_tree);



                    break;
                case 3 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:157:32: date= 'Date'
                    root_0 = this.adaptor.nil();

                    date=this.match(this.input,31,Nvp1Parser.FOLLOW_31_in_rule_DataType632); 
                    date_tree = this.adaptor.create(date);
                    this.adaptor.addChild(root_0, date_tree);



                    break;

            }
            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    },

    // inline static return class
    rule_MsgStereo_return: (function() {
        Nvp1Parser.rule_MsgStereo_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_MsgStereo_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:160:1: rule_MsgStereo : (gET= 'GET' | pOST= 'POST' | camel= 'Camel' | jS= 'JS' | java= 'Java' );
    // $ANTLR start "rule_MsgStereo"
    rule_MsgStereo: function() {
        var retval = new Nvp1Parser.rule_MsgStereo_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var gET = null;
        var pOST = null;
        var camel = null;
        var jS = null;
        var java = null;

        var gET_tree=null;
        var pOST_tree=null;
        var camel_tree=null;
        var jS_tree=null;
        var java_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:160:15: (gET= 'GET' | pOST= 'POST' | camel= 'Camel' | jS= 'JS' | java= 'Java' )
            var alt29=5;
            switch ( this.input.LA(1) ) {
            case 32:
                alt29=1;
                break;
            case 33:
                alt29=2;
                break;
            case 34:
                alt29=3;
                break;
            case 35:
                alt29=4;
                break;
            case 36:
                alt29=5;
                break;
            default:
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 29, 0, this.input);

                throw nvae;
            }

            switch (alt29) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:161:2: gET= 'GET'
                    root_0 = this.adaptor.nil();

                    gET=this.match(this.input,32,Nvp1Parser.FOLLOW_32_in_rule_MsgStereo643); 
                    gET_tree = this.adaptor.create(gET);
                    this.adaptor.addChild(root_0, gET_tree);



                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:161:14: pOST= 'POST'
                    root_0 = this.adaptor.nil();

                    pOST=this.match(this.input,33,Nvp1Parser.FOLLOW_33_in_rule_MsgStereo649); 
                    pOST_tree = this.adaptor.create(pOST);
                    this.adaptor.addChild(root_0, pOST_tree);



                    break;
                case 3 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:161:28: camel= 'Camel'
                    root_0 = this.adaptor.nil();

                    camel=this.match(this.input,34,Nvp1Parser.FOLLOW_34_in_rule_MsgStereo655); 
                    camel_tree = this.adaptor.create(camel);
                    this.adaptor.addChild(root_0, camel_tree);



                    break;
                case 4 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:161:44: jS= 'JS'
                    root_0 = this.adaptor.nil();

                    jS=this.match(this.input,35,Nvp1Parser.FOLLOW_35_in_rule_MsgStereo661); 
                    jS_tree = this.adaptor.create(jS);
                    this.adaptor.addChild(root_0, jS_tree);



                    break;
                case 5 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:161:54: java= 'Java'
                    root_0 = this.adaptor.nil();

                    java=this.match(this.input,36,Nvp1Parser.FOLLOW_36_in_rule_MsgStereo667); 
                    java_tree = this.adaptor.create(java);
                    this.adaptor.addChild(root_0, java_tree);



                    break;

            }
            retval.stop = this.input.LT(-1);

            retval.tree = this.adaptor.rulePostProcessing(root_0);
            this.adaptor.setTokenBoundaries(retval.tree, retval.start, retval.stop);

        }
        catch (re) {
            if (re instanceof org.antlr.runtime.RecognitionException) {
                this.reportError(re);
                this.recover(this.input,re);
                retval.tree = this.adaptor.errorNode(this.input, retval.start, this.input.LT(-1), re);
            } else {
                throw re;
            }
        }
        finally {
        }
        return retval;
    }

    // Delegated rules




}, true); // important to pass true to overwrite default implementations

 

// public class variables
org.antlr.lang.augmentObject(Nvp1Parser, {
    tokenNames: ["<invalid>", "<EOR>", "<DOWN>", "<UP>", "ID", "STRING", "INT", "COMMENT", "WS", "'[['", "':'", "']]'", "'}'", "'$if'", "'$expect'", "'$msg'", "'$val'", "'<'", "'>'", "'$when'", "'=>'", "'$mock'", "'$opt'", "'='", "'('", "','", "')'", "'.*'", "'.'", "'String'", "'Int'", "'Date'", "'GET'", "'POST'", "'Camel'", "'JS'", "'Java'"],
    FOLLOW_rule_AbstractElement_in_rule_DomainModel67: new org.antlr.runtime.BitSet([0x0069D200, 0x00000000]),
    FOLLOW_EOF_in_rule_DomainModel70: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Expect_in_rule_AbstractElement85: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Msg_in_rule_AbstractElement89: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_When_in_rule_AbstractElement93: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Option_in_rule_AbstractElement97: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Val_in_rule_AbstractElement101: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Mock_in_rule_AbstractElement105: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Topic_in_rule_AbstractElement109: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Braq_in_rule_AbstractElement113: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_9_in_rule_Topic129: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_Topic133: new org.antlr.runtime.BitSet([0x00000C00, 0x00000000]),
    FOLLOW_10_in_rule_Topic136: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_Topic140: new org.antlr.runtime.BitSet([0x00000800, 0x00000000]),
    FOLLOW_11_in_rule_Topic144: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_12_in_rule_Braq159: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_ExpectM_in_rule_Expect174: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_ExpectV_in_rule_Expect178: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_13_in_rule_Condition193: new org.antlr.runtime.BitSet([0x01000000, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Condition197: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_14_in_rule_ExpectM212: new org.antlr.runtime.BitSet([0x00008000, 0x00000000]),
    FOLLOW_15_in_rule_ExpectM215: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_ExpectM219: new org.antlr.runtime.BitSet([0x01002002, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_ExpectM223: new org.antlr.runtime.BitSet([0x00002002, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_ExpectM230: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_14_in_rule_ExpectV246: new org.antlr.runtime.BitSet([0x00010000, 0x00000000]),
    FOLLOW_16_in_rule_ExpectV249: new org.antlr.runtime.BitSet([0x00002012, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_ExpectV253: new org.antlr.runtime.BitSet([0x00002002, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_ExpectV260: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_16_in_rule_Val276: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_Val280: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_15_in_rule_Msg295: new org.antlr.runtime.BitSet([0x00020010, 0x00000000]),
    FOLLOW_17_in_rule_Msg298: new org.antlr.runtime.BitSet([0x00000000, 0x0000001F]),
    FOLLOW_rule_MsgStereo_in_rule_Msg302: new org.antlr.runtime.BitSet([0x00040000, 0x00000000]),
    FOLLOW_18_in_rule_Msg304: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_Msg310: new org.antlr.runtime.BitSet([0x01000002, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Msg314: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_19_in_rule_When327: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_ID_in_rule_When331: new org.antlr.runtime.BitSet([0x01102000, 0x00000000]),
    FOLLOW_rule_Attrs_in_rule_When335: new org.antlr.runtime.BitSet([0x00102000, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_When340: new org.antlr.runtime.BitSet([0x00100000, 0x00000000]),
    FOLLOW_20_in_rule_When343: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_ID_in_rule_When347: new org.antlr.runtime.BitSet([0x01000002, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_When351: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_21_in_rule_Mock365: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_ID_in_rule_Mock369: new org.antlr.runtime.BitSet([0x01102000, 0x00000000]),
    FOLLOW_rule_Attrs_in_rule_Mock373: new org.antlr.runtime.BitSet([0x00102000, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_Mock378: new org.antlr.runtime.BitSet([0x00100000, 0x00000000]),
    FOLLOW_20_in_rule_Mock381: new org.antlr.runtime.BitSet([0x01000002, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Mock385: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_22_in_rule_Option401: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_Option405: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_ID_in_rule_AttrSpec420: new org.antlr.runtime.BitSet([0x00800402, 0x00000000]),
    FOLLOW_10_in_rule_AttrSpec423: new org.antlr.runtime.BitSet([0xE0000000, 0x00000000]),
    FOLLOW_rule_DataType_in_rule_AttrSpec427: new org.antlr.runtime.BitSet([0x00800002, 0x00000000]),
    FOLLOW_23_in_rule_AttrSpec432: new org.antlr.runtime.BitSet([0x00000070, 0x00000000]),
    FOLLOW_rule_EXPR_in_rule_AttrSpec436: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_ID_in_rule_Attr452: new org.antlr.runtime.BitSet([0x00800402, 0x00000000]),
    FOLLOW_10_in_rule_Attr455: new org.antlr.runtime.BitSet([0xE0000000, 0x00000000]),
    FOLLOW_rule_DataType_in_rule_Attr459: new org.antlr.runtime.BitSet([0x00800002, 0x00000000]),
    FOLLOW_23_in_rule_Attr464: new org.antlr.runtime.BitSet([0x00000070, 0x00000000]),
    FOLLOW_rule_EXPR_in_rule_Attr468: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_ID_in_rule_EXPR484: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_STRING_in_rule_EXPR490: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_INT_in_rule_EXPR496: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_24_in_rule_Attrs513: new org.antlr.runtime.BitSet([0x04000010, 0x00000000]),
    FOLLOW_rule_Attr_in_rule_Attrs518: new org.antlr.runtime.BitSet([0x06000000, 0x00000000]),
    FOLLOW_25_in_rule_Attrs521: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_Attr_in_rule_Attrs525: new org.antlr.runtime.BitSet([0x06000000, 0x00000000]),
    FOLLOW_26_in_rule_Attrs531: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_24_in_rule_AttrSpecs548: new org.antlr.runtime.BitSet([0x04000010, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_AttrSpecs553: new org.antlr.runtime.BitSet([0x06000000, 0x00000000]),
    FOLLOW_25_in_rule_AttrSpecs556: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_AttrSpecs560: new org.antlr.runtime.BitSet([0x06000000, 0x00000000]),
    FOLLOW_26_in_rule_AttrSpecs566: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_QualifiedNameWithWildCard585: new org.antlr.runtime.BitSet([0x08000002, 0x00000000]),
    FOLLOW_27_in_rule_QualifiedNameWithWildCard587: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_ID_in_rule_QualifiedName602: new org.antlr.runtime.BitSet([0x10000002, 0x00000000]),
    FOLLOW_28_in_rule_QualifiedName605: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_ID_in_rule_QualifiedName607: new org.antlr.runtime.BitSet([0x10000002, 0x00000000]),
    FOLLOW_29_in_rule_DataType620: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_30_in_rule_DataType626: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_31_in_rule_DataType632: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_32_in_rule_MsgStereo643: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_33_in_rule_MsgStereo649: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_34_in_rule_MsgStereo655: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_35_in_rule_MsgStereo661: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_36_in_rule_MsgStereo667: new org.antlr.runtime.BitSet([0x00000002, 0x00000000])
});

})();