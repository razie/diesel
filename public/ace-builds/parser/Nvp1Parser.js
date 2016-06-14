// $ANTLR 3.3 avr. 19, 2016 01:13:22 /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g 2016-06-13 14:09:39



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

                if ( ((LA1_0>=10 && LA1_0<=12)||LA1_0==15||(LA1_0>=17 && LA1_0<=18)) ) {
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:28:1: rule_AbstractElement : ( rule_Expect | rule_Msg | rule_When | rule_Option | rule_Val | rule_Mock );
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


        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:28:21: ( rule_Expect | rule_Msg | rule_When | rule_Option | rule_Val | rule_Mock )
            var alt2=6;
            switch ( this.input.LA(1) ) {
            case 10:
                alt2=1;
                break;
            case 11:
                alt2=2;
                break;
            case 15:
                alt2=3;
                break;
            case 18:
                alt2=4;
                break;
            case 12:
                alt2=5;
                break;
            case 17:
                alt2=6;
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
    rule_Expect_return: (function() {
        Nvp1Parser.rule_Expect_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Expect_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:36:1: rule_Expect : ( rule_ExpectM | rule_ExpectV );
    // $ANTLR start "rule_Expect"
    rule_Expect: function() {
        var retval = new Nvp1Parser.rule_Expect_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

         var rule_ExpectM8 = null;
         var rule_ExpectV9 = null;


        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:36:12: ( rule_ExpectM | rule_ExpectV )
            var alt3=2;
            var LA3_0 = this.input.LA(1);

            if ( (LA3_0==10) ) {
                var LA3_1 = this.input.LA(2);

                if ( (LA3_1==11) ) {
                    alt3=1;
                }
                else if ( (LA3_1==12) ) {
                    alt3=2;
                }
                else {
                    var nvae =
                        new org.antlr.runtime.NoViableAltException("", 3, 1, this.input);

                    throw nvae;
                }
            }
            else {
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 3, 0, this.input);

                throw nvae;
            }
            switch (alt3) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:5: rule_ExpectM
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_ExpectM_in_rule_Expect121);
                    rule_ExpectM8=this.rule_ExpectM();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_ExpectM8.getTree());


                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:20: rule_ExpectV
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_ExpectV_in_rule_Expect125);
                    rule_ExpectV9=this.rule_ExpectV();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_ExpectV9.getTree());


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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:43:1: rule_Condition : '$if' attrs= rule_AttrSpecs ;
    // $ANTLR start "rule_Condition"
    rule_Condition: function() {
        var retval = new Nvp1Parser.rule_Condition_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal10 = null;
         var attrs = null;

        var string_literal10_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:43:15: ( '$if' attrs= rule_AttrSpecs )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:44:5: '$if' attrs= rule_AttrSpecs
            root_0 = this.adaptor.nil();

            string_literal10=this.match(this.input,9,Nvp1Parser.FOLLOW_9_in_rule_Condition140); 
            string_literal10_tree = this.adaptor.create(string_literal10);
            this.adaptor.addChild(root_0, string_literal10_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Condition144);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:50:1: rule_ExpectM : '$expect' ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? ) (cond= rule_Condition )? ;
    // $ANTLR start "rule_ExpectM"
    rule_ExpectM: function() {
        var retval = new Nvp1Parser.rule_ExpectM_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal11 = null;
        var string_literal12 = null;
         var name = null;
         var attrs = null;
         var cond = null;

        var string_literal11_tree=null;
        var string_literal12_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:50:13: ( '$expect' ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? ) (cond= rule_Condition )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:5: '$expect' ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? ) (cond= rule_Condition )?
            root_0 = this.adaptor.nil();

            string_literal11=this.match(this.input,10,Nvp1Parser.FOLLOW_10_in_rule_ExpectM159); 
            string_literal11_tree = this.adaptor.create(string_literal11);
            this.adaptor.addChild(root_0, string_literal11_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:15: ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:16: '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )?
            string_literal12=this.match(this.input,11,Nvp1Parser.FOLLOW_11_in_rule_ExpectM162); 
            string_literal12_tree = this.adaptor.create(string_literal12);
            this.adaptor.addChild(root_0, string_literal12_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_ExpectM166);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:52: (attrs= rule_AttrSpecs )?
            var alt4=2;
            var LA4_0 = this.input.LA(1);

            if ( (LA4_0==21) ) {
                alt4=1;
            }
            switch (alt4) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:52: attrs= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_ExpectM170);
                    attrs=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());


                    break;

            }




            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:70: (cond= rule_Condition )?
            var alt5=2;
            var LA5_0 = this.input.LA(1);

            if ( (LA5_0==9) ) {
                alt5=1;
            }
            switch (alt5) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:71: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_ExpectM177);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:56:1: rule_ExpectV : '$expect' ( '$val' (p= rule_AttrSpec )? ) (cond= rule_Condition )? ;
    // $ANTLR start "rule_ExpectV"
    rule_ExpectV: function() {
        var retval = new Nvp1Parser.rule_ExpectV_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal13 = null;
        var string_literal14 = null;
         var p = null;
         var cond = null;

        var string_literal13_tree=null;
        var string_literal14_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:56:13: ( '$expect' ( '$val' (p= rule_AttrSpec )? ) (cond= rule_Condition )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:57:5: '$expect' ( '$val' (p= rule_AttrSpec )? ) (cond= rule_Condition )?
            root_0 = this.adaptor.nil();

            string_literal13=this.match(this.input,10,Nvp1Parser.FOLLOW_10_in_rule_ExpectV193); 
            string_literal13_tree = this.adaptor.create(string_literal13);
            this.adaptor.addChild(root_0, string_literal13_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:57:15: ( '$val' (p= rule_AttrSpec )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:57:16: '$val' (p= rule_AttrSpec )?
            string_literal14=this.match(this.input,12,Nvp1Parser.FOLLOW_12_in_rule_ExpectV196); 
            string_literal14_tree = this.adaptor.create(string_literal14);
            this.adaptor.addChild(root_0, string_literal14_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:57:24: (p= rule_AttrSpec )?
            var alt6=2;
            var LA6_0 = this.input.LA(1);

            if ( (LA6_0==ID) ) {
                alt6=1;
            }
            switch (alt6) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:57:24: p= rule_AttrSpec
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_ExpectV200);
                    p=this.rule_AttrSpec();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, p.getTree());


                    break;

            }




            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:57:41: (cond= rule_Condition )?
            var alt7=2;
            var LA7_0 = this.input.LA(1);

            if ( (LA7_0==9) ) {
                alt7=1;
            }
            switch (alt7) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:57:42: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_ExpectV207);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:62:1: rule_Val : '$val' p= rule_AttrSpec ;
    // $ANTLR start "rule_Val"
    rule_Val: function() {
        var retval = new Nvp1Parser.rule_Val_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal15 = null;
         var p = null;

        var string_literal15_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:62:9: ( '$val' p= rule_AttrSpec )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:63:5: '$val' p= rule_AttrSpec
            root_0 = this.adaptor.nil();

            string_literal15=this.match(this.input,12,Nvp1Parser.FOLLOW_12_in_rule_Val223); 
            string_literal15_tree = this.adaptor.create(string_literal15);
            this.adaptor.addChild(root_0, string_literal15_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_Val227);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:69:1: rule_Msg : '$msg' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? ;
    // $ANTLR start "rule_Msg"
    rule_Msg: function() {
        var retval = new Nvp1Parser.rule_Msg_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal16 = null;
        var char_literal17 = null;
        var char_literal18 = null;
         var stype = null;
         var name = null;
         var attrs = null;

        var string_literal16_tree=null;
        var char_literal17_tree=null;
        var char_literal18_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:69:9: ( '$msg' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:70:5: '$msg' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )?
            root_0 = this.adaptor.nil();

            string_literal16=this.match(this.input,11,Nvp1Parser.FOLLOW_11_in_rule_Msg242); 
            string_literal16_tree = this.adaptor.create(string_literal16);
            this.adaptor.addChild(root_0, string_literal16_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:70:12: ( '<' stype= rule_MsgStereo '>' )?
            var alt8=2;
            var LA8_0 = this.input.LA(1);

            if ( (LA8_0==13) ) {
                alt8=1;
            }
            switch (alt8) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:70:13: '<' stype= rule_MsgStereo '>'
                    char_literal17=this.match(this.input,13,Nvp1Parser.FOLLOW_13_in_rule_Msg245); 
                    char_literal17_tree = this.adaptor.create(char_literal17);
                    this.adaptor.addChild(root_0, char_literal17_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_MsgStereo_in_rule_Msg249);
                    stype=this.rule_MsgStereo();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, stype.getTree());
                    char_literal18=this.match(this.input,14,Nvp1Parser.FOLLOW_14_in_rule_Msg251); 
                    char_literal18_tree = this.adaptor.create(char_literal18);
                    this.adaptor.addChild(root_0, char_literal18_tree);



                    break;

            }

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_Msg257);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:70:73: (attrs= rule_AttrSpecs )?
            var alt9=2;
            var LA9_0 = this.input.LA(1);

            if ( (LA9_0==21) ) {
                alt9=1;
            }
            switch (alt9) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:70:73: attrs= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Msg261);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:76:1: rule_When : '$when' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' z= ID (za= rule_AttrSpecs )? ;
    // $ANTLR start "rule_When"
    rule_When: function() {
        var retval = new Nvp1Parser.rule_When_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var a = null;
        var z = null;
        var string_literal19 = null;
        var string_literal20 = null;
         var aa = null;
         var cond = null;
         var za = null;

        var a_tree=null;
        var z_tree=null;
        var string_literal19_tree=null;
        var string_literal20_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:76:10: ( '$when' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' z= ID (za= rule_AttrSpecs )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:77:2: '$when' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' z= ID (za= rule_AttrSpecs )?
            root_0 = this.adaptor.nil();

            string_literal19=this.match(this.input,15,Nvp1Parser.FOLLOW_15_in_rule_When274); 
            string_literal19_tree = this.adaptor.create(string_literal19);
            this.adaptor.addChild(root_0, string_literal19_tree);

            a=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_When278); 
            a_tree = this.adaptor.create(a);
            this.adaptor.addChild(root_0, a_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:77:17: (aa= rule_Attrs )?
            var alt10=2;
            var LA10_0 = this.input.LA(1);

            if ( (LA10_0==21) ) {
                alt10=1;
            }
            switch (alt10) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:77:17: aa= rule_Attrs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attrs_in_rule_When282);
                    aa=this.rule_Attrs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, aa.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:77:34: (cond= rule_Condition )?
            var alt11=2;
            var LA11_0 = this.input.LA(1);

            if ( (LA11_0==9) ) {
                alt11=1;
            }
            switch (alt11) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:77:34: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_When287);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            string_literal20=this.match(this.input,16,Nvp1Parser.FOLLOW_16_in_rule_When290); 
            string_literal20_tree = this.adaptor.create(string_literal20);
            this.adaptor.addChild(root_0, string_literal20_tree);

            z=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_When294); 
            z_tree = this.adaptor.create(z);
            this.adaptor.addChild(root_0, z_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:77:63: (za= rule_AttrSpecs )?
            var alt12=2;
            var LA12_0 = this.input.LA(1);

            if ( (LA12_0==21) ) {
                alt12=1;
            }
            switch (alt12) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:77:63: za= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_When298);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:83:1: rule_Mock : '$mock' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' (za= rule_AttrSpecs )? ;
    // $ANTLR start "rule_Mock"
    rule_Mock: function() {
        var retval = new Nvp1Parser.rule_Mock_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var a = null;
        var string_literal21 = null;
        var string_literal22 = null;
         var aa = null;
         var cond = null;
         var za = null;

        var a_tree=null;
        var string_literal21_tree=null;
        var string_literal22_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:83:10: ( '$mock' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' (za= rule_AttrSpecs )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:84:2: '$mock' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' (za= rule_AttrSpecs )?
            root_0 = this.adaptor.nil();

            string_literal21=this.match(this.input,17,Nvp1Parser.FOLLOW_17_in_rule_Mock312); 
            string_literal21_tree = this.adaptor.create(string_literal21);
            this.adaptor.addChild(root_0, string_literal21_tree);

            a=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_Mock316); 
            a_tree = this.adaptor.create(a);
            this.adaptor.addChild(root_0, a_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:84:17: (aa= rule_Attrs )?
            var alt13=2;
            var LA13_0 = this.input.LA(1);

            if ( (LA13_0==21) ) {
                alt13=1;
            }
            switch (alt13) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:84:17: aa= rule_Attrs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attrs_in_rule_Mock320);
                    aa=this.rule_Attrs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, aa.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:84:34: (cond= rule_Condition )?
            var alt14=2;
            var LA14_0 = this.input.LA(1);

            if ( (LA14_0==9) ) {
                alt14=1;
            }
            switch (alt14) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:84:34: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_Mock325);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            string_literal22=this.match(this.input,16,Nvp1Parser.FOLLOW_16_in_rule_Mock328); 
            string_literal22_tree = this.adaptor.create(string_literal22);
            this.adaptor.addChild(root_0, string_literal22_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:84:58: (za= rule_AttrSpecs )?
            var alt15=2;
            var LA15_0 = this.input.LA(1);

            if ( (LA15_0==21) ) {
                alt15=1;
            }
            switch (alt15) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:84:58: za= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Mock332);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:90:1: rule_Option : '$opt' attr= rule_AttrSpec ;
    // $ANTLR start "rule_Option"
    rule_Option: function() {
        var retval = new Nvp1Parser.rule_Option_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal23 = null;
         var attr = null;

        var string_literal23_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:90:12: ( '$opt' attr= rule_AttrSpec )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:91:5: '$opt' attr= rule_AttrSpec
            root_0 = this.adaptor.nil();

            string_literal23=this.match(this.input,18,Nvp1Parser.FOLLOW_18_in_rule_Option348); 
            string_literal23_tree = this.adaptor.create(string_literal23);
            this.adaptor.addChild(root_0, string_literal23_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_Option352);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:97:1: rule_AttrSpec : name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? ;
    // $ANTLR start "rule_AttrSpec"
    rule_AttrSpec: function() {
        var retval = new Nvp1Parser.rule_AttrSpec_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var name = null;
        var char_literal24 = null;
        var char_literal25 = null;
         var ttype = null;
         var eexpr = null;

        var name_tree=null;
        var char_literal24_tree=null;
        var char_literal25_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:97:14: (name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:98:3: name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )?
            root_0 = this.adaptor.nil();

            name=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_AttrSpec367); 
            name_tree = this.adaptor.create(name);
            this.adaptor.addChild(root_0, name_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:98:11: ( ':' ttype= rule_DataType )?
            var alt16=2;
            var LA16_0 = this.input.LA(1);

            if ( (LA16_0==19) ) {
                alt16=1;
            }
            switch (alt16) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:98:12: ':' ttype= rule_DataType
                    char_literal24=this.match(this.input,19,Nvp1Parser.FOLLOW_19_in_rule_AttrSpec370); 
                    char_literal24_tree = this.adaptor.create(char_literal24);
                    this.adaptor.addChild(root_0, char_literal24_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_DataType_in_rule_AttrSpec374);
                    ttype=this.rule_DataType();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, ttype.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:98:38: ( '=' eexpr= rule_EXPR )?
            var alt17=2;
            var LA17_0 = this.input.LA(1);

            if ( (LA17_0==20) ) {
                alt17=1;
            }
            switch (alt17) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:98:39: '=' eexpr= rule_EXPR
                    char_literal25=this.match(this.input,20,Nvp1Parser.FOLLOW_20_in_rule_AttrSpec379); 
                    char_literal25_tree = this.adaptor.create(char_literal25);
                    this.adaptor.addChild(root_0, char_literal25_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_EXPR_in_rule_AttrSpec383);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:103:1: rule_Attr : name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? ;
    // $ANTLR start "rule_Attr"
    rule_Attr: function() {
        var retval = new Nvp1Parser.rule_Attr_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var name = null;
        var char_literal26 = null;
        var char_literal27 = null;
         var ttype = null;
         var eexpr = null;

        var name_tree=null;
        var char_literal26_tree=null;
        var char_literal27_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:103:10: (name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:104:3: name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )?
            root_0 = this.adaptor.nil();

            name=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_Attr399); 
            name_tree = this.adaptor.create(name);
            this.adaptor.addChild(root_0, name_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:104:11: ( ':' ttype= rule_DataType )?
            var alt18=2;
            var LA18_0 = this.input.LA(1);

            if ( (LA18_0==19) ) {
                alt18=1;
            }
            switch (alt18) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:104:12: ':' ttype= rule_DataType
                    char_literal26=this.match(this.input,19,Nvp1Parser.FOLLOW_19_in_rule_Attr402); 
                    char_literal26_tree = this.adaptor.create(char_literal26);
                    this.adaptor.addChild(root_0, char_literal26_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_DataType_in_rule_Attr406);
                    ttype=this.rule_DataType();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, ttype.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:104:38: ( '=' eexpr= rule_EXPR )?
            var alt19=2;
            var LA19_0 = this.input.LA(1);

            if ( (LA19_0==20) ) {
                alt19=1;
            }
            switch (alt19) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:104:39: '=' eexpr= rule_EXPR
                    char_literal27=this.match(this.input,20,Nvp1Parser.FOLLOW_20_in_rule_Attr411); 
                    char_literal27_tree = this.adaptor.create(char_literal27);
                    this.adaptor.addChild(root_0, char_literal27_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_EXPR_in_rule_Attr415);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:109:1: rule_EXPR : (parm= ID | svalue= STRING | ivalue= INT );
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
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:109:10: (parm= ID | svalue= STRING | ivalue= INT )
            var alt20=3;
            switch ( this.input.LA(1) ) {
            case ID:
                alt20=1;
                break;
            case STRING:
                alt20=2;
                break;
            case INT:
                alt20=3;
                break;
            default:
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 20, 0, this.input);

                throw nvae;
            }

            switch (alt20) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:110:3: parm= ID
                    root_0 = this.adaptor.nil();

                    parm=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_EXPR431); 
                    parm_tree = this.adaptor.create(parm);
                    this.adaptor.addChild(root_0, parm_tree);



                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:110:13: svalue= STRING
                    root_0 = this.adaptor.nil();

                    svalue=this.match(this.input,STRING,Nvp1Parser.FOLLOW_STRING_in_rule_EXPR437); 
                    svalue_tree = this.adaptor.create(svalue);
                    this.adaptor.addChild(root_0, svalue_tree);



                    break;
                case 3 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:110:29: ivalue= INT
                    root_0 = this.adaptor.nil();

                    ivalue=this.match(this.input,INT,Nvp1Parser.FOLLOW_INT_in_rule_EXPR443); 
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:115:1: rule_Attrs : ( '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')' ) ;
    // $ANTLR start "rule_Attrs"
    rule_Attrs: function() {
        var retval = new Nvp1Parser.rule_Attrs_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal28 = null;
        var char_literal29 = null;
        var char_literal30 = null;
        var list_attrs=null;
        var attrs = null;
        var char_literal28_tree=null;
        var char_literal29_tree=null;
        var char_literal30_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:115:11: ( ( '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')' ) )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:5: ( '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')' )
            root_0 = this.adaptor.nil();

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:5: ( '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:6: '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')'
            char_literal28=this.match(this.input,21,Nvp1Parser.FOLLOW_21_in_rule_Attrs460); 
            char_literal28_tree = this.adaptor.create(char_literal28);
            this.adaptor.addChild(root_0, char_literal28_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:10: (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )?
            var alt22=2;
            var LA22_0 = this.input.LA(1);

            if ( (LA22_0==ID) ) {
                alt22=1;
            }
            switch (alt22) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:11: attrs+= rule_Attr ( ',' attrs+= rule_Attr )*
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attr_in_rule_Attrs465);
                    attrs=this.rule_Attr();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());
                    if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                    list_attrs.push(attrs.getTree());

                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:28: ( ',' attrs+= rule_Attr )*
                    loop21:
                    do {
                        var alt21=2;
                        var LA21_0 = this.input.LA(1);

                        if ( (LA21_0==22) ) {
                            alt21=1;
                        }


                        switch (alt21) {
                        case 1 :
                            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:29: ',' attrs+= rule_Attr
                            char_literal29=this.match(this.input,22,Nvp1Parser.FOLLOW_22_in_rule_Attrs468); 
                            char_literal29_tree = this.adaptor.create(char_literal29);
                            this.adaptor.addChild(root_0, char_literal29_tree);

                            this.pushFollow(Nvp1Parser.FOLLOW_rule_Attr_in_rule_Attrs472);
                            attrs=this.rule_Attr();

                            this.state._fsp--;

                            this.adaptor.addChild(root_0, attrs.getTree());
                            if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                            list_attrs.push(attrs.getTree());



                            break;

                        default :
                            break loop21;
                        }
                    } while (true);



                    break;

            }

            char_literal30=this.match(this.input,23,Nvp1Parser.FOLLOW_23_in_rule_Attrs478); 
            char_literal30_tree = this.adaptor.create(char_literal30);
            this.adaptor.addChild(root_0, char_literal30_tree);







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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:122:1: rule_AttrSpecs : ( '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')' ) ;
    // $ANTLR start "rule_AttrSpecs"
    rule_AttrSpecs: function() {
        var retval = new Nvp1Parser.rule_AttrSpecs_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal31 = null;
        var char_literal32 = null;
        var char_literal33 = null;
        var list_attrs=null;
        var attrs = null;
        var char_literal31_tree=null;
        var char_literal32_tree=null;
        var char_literal33_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:122:15: ( ( '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')' ) )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:5: ( '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')' )
            root_0 = this.adaptor.nil();

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:5: ( '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:6: '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')'
            char_literal31=this.match(this.input,21,Nvp1Parser.FOLLOW_21_in_rule_AttrSpecs495); 
            char_literal31_tree = this.adaptor.create(char_literal31);
            this.adaptor.addChild(root_0, char_literal31_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:10: (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )?
            var alt24=2;
            var LA24_0 = this.input.LA(1);

            if ( (LA24_0==ID) ) {
                alt24=1;
            }
            switch (alt24) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:11: attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )*
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_AttrSpecs500);
                    attrs=this.rule_AttrSpec();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());
                    if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                    list_attrs.push(attrs.getTree());

                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:32: ( ',' attrs+= rule_AttrSpec )*
                    loop23:
                    do {
                        var alt23=2;
                        var LA23_0 = this.input.LA(1);

                        if ( (LA23_0==22) ) {
                            alt23=1;
                        }


                        switch (alt23) {
                        case 1 :
                            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:33: ',' attrs+= rule_AttrSpec
                            char_literal32=this.match(this.input,22,Nvp1Parser.FOLLOW_22_in_rule_AttrSpecs503); 
                            char_literal32_tree = this.adaptor.create(char_literal32);
                            this.adaptor.addChild(root_0, char_literal32_tree);

                            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_AttrSpecs507);
                            attrs=this.rule_AttrSpec();

                            this.state._fsp--;

                            this.adaptor.addChild(root_0, attrs.getTree());
                            if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                            list_attrs.push(attrs.getTree());



                            break;

                        default :
                            break loop23;
                        }
                    } while (true);



                    break;

            }

            char_literal33=this.match(this.input,23,Nvp1Parser.FOLLOW_23_in_rule_AttrSpecs513); 
            char_literal33_tree = this.adaptor.create(char_literal33);
            this.adaptor.addChild(root_0, char_literal33_tree);







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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:132:1: rule_QualifiedNameWithWildCard : rule_QualifiedName ( '.*' )? ;
    // $ANTLR start "rule_QualifiedNameWithWildCard"
    rule_QualifiedNameWithWildCard: function() {
        var retval = new Nvp1Parser.rule_QualifiedNameWithWildCard_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal35 = null;
         var rule_QualifiedName34 = null;

        var string_literal35_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:132:31: ( rule_QualifiedName ( '.*' )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:133:5: rule_QualifiedName ( '.*' )?
            root_0 = this.adaptor.nil();

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_QualifiedNameWithWildCard532);
            rule_QualifiedName34=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, rule_QualifiedName34.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:133:24: ( '.*' )?
            var alt25=2;
            var LA25_0 = this.input.LA(1);

            if ( (LA25_0==24) ) {
                alt25=1;
            }
            switch (alt25) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:133:24: '.*'
                    string_literal35=this.match(this.input,24,Nvp1Parser.FOLLOW_24_in_rule_QualifiedNameWithWildCard534); 
                    string_literal35_tree = this.adaptor.create(string_literal35);
                    this.adaptor.addChild(root_0, string_literal35_tree);



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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:138:1: rule_QualifiedName : ID ( '.' ID )* ;
    // $ANTLR start "rule_QualifiedName"
    rule_QualifiedName: function() {
        var retval = new Nvp1Parser.rule_QualifiedName_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var ID36 = null;
        var char_literal37 = null;
        var ID38 = null;

        var ID36_tree=null;
        var char_literal37_tree=null;
        var ID38_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:138:19: ( ID ( '.' ID )* )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:139:5: ID ( '.' ID )*
            root_0 = this.adaptor.nil();

            ID36=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_QualifiedName549); 
            ID36_tree = this.adaptor.create(ID36);
            this.adaptor.addChild(root_0, ID36_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:139:8: ( '.' ID )*
            loop26:
            do {
                var alt26=2;
                var LA26_0 = this.input.LA(1);

                if ( (LA26_0==25) ) {
                    alt26=1;
                }


                switch (alt26) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:139:9: '.' ID
                    char_literal37=this.match(this.input,25,Nvp1Parser.FOLLOW_25_in_rule_QualifiedName552); 
                    char_literal37_tree = this.adaptor.create(char_literal37);
                    this.adaptor.addChild(root_0, char_literal37_tree);

                    ID38=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_QualifiedName554); 
                    ID38_tree = this.adaptor.create(ID38);
                    this.adaptor.addChild(root_0, ID38_tree);



                    break;

                default :
                    break loop26;
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:142:1: rule_DataType : (string= 'String' | int= 'Int' | date= 'Date' );
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
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:142:14: (string= 'String' | int= 'Int' | date= 'Date' )
            var alt27=3;
            switch ( this.input.LA(1) ) {
            case 26:
                alt27=1;
                break;
            case 27:
                alt27=2;
                break;
            case 28:
                alt27=3;
                break;
            default:
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 27, 0, this.input);

                throw nvae;
            }

            switch (alt27) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:143:2: string= 'String'
                    root_0 = this.adaptor.nil();

                    string=this.match(this.input,26,Nvp1Parser.FOLLOW_26_in_rule_DataType567); 
                    string_tree = this.adaptor.create(string);
                    this.adaptor.addChild(root_0, string_tree);



                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:143:20: int= 'Int'
                    root_0 = this.adaptor.nil();

                    int=this.match(this.input,27,Nvp1Parser.FOLLOW_27_in_rule_DataType573); 
                    int_tree = this.adaptor.create(int);
                    this.adaptor.addChild(root_0, int_tree);



                    break;
                case 3 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:143:32: date= 'Date'
                    root_0 = this.adaptor.nil();

                    date=this.match(this.input,28,Nvp1Parser.FOLLOW_28_in_rule_DataType579); 
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:146:1: rule_MsgStereo : (gET= 'GET' | pOST= 'POST' | camel= 'Camel' | jS= 'JS' | java= 'Java' );
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
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:146:15: (gET= 'GET' | pOST= 'POST' | camel= 'Camel' | jS= 'JS' | java= 'Java' )
            var alt28=5;
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
            case 32:
                alt28=4;
                break;
            case 33:
                alt28=5;
                break;
            default:
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 28, 0, this.input);

                throw nvae;
            }

            switch (alt28) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:147:2: gET= 'GET'
                    root_0 = this.adaptor.nil();

                    gET=this.match(this.input,29,Nvp1Parser.FOLLOW_29_in_rule_MsgStereo590); 
                    gET_tree = this.adaptor.create(gET);
                    this.adaptor.addChild(root_0, gET_tree);



                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:147:14: pOST= 'POST'
                    root_0 = this.adaptor.nil();

                    pOST=this.match(this.input,30,Nvp1Parser.FOLLOW_30_in_rule_MsgStereo596); 
                    pOST_tree = this.adaptor.create(pOST);
                    this.adaptor.addChild(root_0, pOST_tree);



                    break;
                case 3 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:147:28: camel= 'Camel'
                    root_0 = this.adaptor.nil();

                    camel=this.match(this.input,31,Nvp1Parser.FOLLOW_31_in_rule_MsgStereo602); 
                    camel_tree = this.adaptor.create(camel);
                    this.adaptor.addChild(root_0, camel_tree);



                    break;
                case 4 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:147:44: jS= 'JS'
                    root_0 = this.adaptor.nil();

                    jS=this.match(this.input,32,Nvp1Parser.FOLLOW_32_in_rule_MsgStereo608); 
                    jS_tree = this.adaptor.create(jS);
                    this.adaptor.addChild(root_0, jS_tree);



                    break;
                case 5 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:147:54: java= 'Java'
                    root_0 = this.adaptor.nil();

                    java=this.match(this.input,33,Nvp1Parser.FOLLOW_33_in_rule_MsgStereo614); 
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
    tokenNames: ["<invalid>", "<EOR>", "<DOWN>", "<UP>", "ID", "STRING", "INT", "COMMENT", "WS", "'$if'", "'$expect'", "'$msg'", "'$val'", "'<'", "'>'", "'$when'", "'=>'", "'$mock'", "'$opt'", "':'", "'='", "'('", "','", "')'", "'.*'", "'.'", "'String'", "'Int'", "'Date'", "'GET'", "'POST'", "'Camel'", "'JS'", "'Java'"],
    FOLLOW_rule_AbstractElement_in_rule_DomainModel67: new org.antlr.runtime.BitSet([0x00069C00, 0x00000000]),
    FOLLOW_EOF_in_rule_DomainModel70: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Expect_in_rule_AbstractElement85: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Msg_in_rule_AbstractElement89: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_When_in_rule_AbstractElement93: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Option_in_rule_AbstractElement97: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Val_in_rule_AbstractElement101: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Mock_in_rule_AbstractElement105: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_ExpectM_in_rule_Expect121: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_ExpectV_in_rule_Expect125: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_9_in_rule_Condition140: new org.antlr.runtime.BitSet([0x00200000, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Condition144: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_10_in_rule_ExpectM159: new org.antlr.runtime.BitSet([0x00000800, 0x00000000]),
    FOLLOW_11_in_rule_ExpectM162: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_ExpectM166: new org.antlr.runtime.BitSet([0x00200202, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_ExpectM170: new org.antlr.runtime.BitSet([0x00000202, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_ExpectM177: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_10_in_rule_ExpectV193: new org.antlr.runtime.BitSet([0x00001000, 0x00000000]),
    FOLLOW_12_in_rule_ExpectV196: new org.antlr.runtime.BitSet([0x00000212, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_ExpectV200: new org.antlr.runtime.BitSet([0x00000202, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_ExpectV207: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_12_in_rule_Val223: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_Val227: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_11_in_rule_Msg242: new org.antlr.runtime.BitSet([0x00002010, 0x00000000]),
    FOLLOW_13_in_rule_Msg245: new org.antlr.runtime.BitSet([0xE0000000, 0x00000003]),
    FOLLOW_rule_MsgStereo_in_rule_Msg249: new org.antlr.runtime.BitSet([0x00004000, 0x00000000]),
    FOLLOW_14_in_rule_Msg251: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_Msg257: new org.antlr.runtime.BitSet([0x00200002, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Msg261: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_15_in_rule_When274: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_ID_in_rule_When278: new org.antlr.runtime.BitSet([0x00210200, 0x00000000]),
    FOLLOW_rule_Attrs_in_rule_When282: new org.antlr.runtime.BitSet([0x00010200, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_When287: new org.antlr.runtime.BitSet([0x00010000, 0x00000000]),
    FOLLOW_16_in_rule_When290: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_ID_in_rule_When294: new org.antlr.runtime.BitSet([0x00200002, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_When298: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_17_in_rule_Mock312: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_ID_in_rule_Mock316: new org.antlr.runtime.BitSet([0x00210200, 0x00000000]),
    FOLLOW_rule_Attrs_in_rule_Mock320: new org.antlr.runtime.BitSet([0x00010200, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_Mock325: new org.antlr.runtime.BitSet([0x00010000, 0x00000000]),
    FOLLOW_16_in_rule_Mock328: new org.antlr.runtime.BitSet([0x00200002, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Mock332: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_18_in_rule_Option348: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_Option352: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_ID_in_rule_AttrSpec367: new org.antlr.runtime.BitSet([0x00180002, 0x00000000]),
    FOLLOW_19_in_rule_AttrSpec370: new org.antlr.runtime.BitSet([0x1C000000, 0x00000000]),
    FOLLOW_rule_DataType_in_rule_AttrSpec374: new org.antlr.runtime.BitSet([0x00100002, 0x00000000]),
    FOLLOW_20_in_rule_AttrSpec379: new org.antlr.runtime.BitSet([0x00000070, 0x00000000]),
    FOLLOW_rule_EXPR_in_rule_AttrSpec383: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_ID_in_rule_Attr399: new org.antlr.runtime.BitSet([0x00180002, 0x00000000]),
    FOLLOW_19_in_rule_Attr402: new org.antlr.runtime.BitSet([0x1C000000, 0x00000000]),
    FOLLOW_rule_DataType_in_rule_Attr406: new org.antlr.runtime.BitSet([0x00100002, 0x00000000]),
    FOLLOW_20_in_rule_Attr411: new org.antlr.runtime.BitSet([0x00000070, 0x00000000]),
    FOLLOW_rule_EXPR_in_rule_Attr415: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_ID_in_rule_EXPR431: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_STRING_in_rule_EXPR437: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_INT_in_rule_EXPR443: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_21_in_rule_Attrs460: new org.antlr.runtime.BitSet([0x00800010, 0x00000000]),
    FOLLOW_rule_Attr_in_rule_Attrs465: new org.antlr.runtime.BitSet([0x00C00000, 0x00000000]),
    FOLLOW_22_in_rule_Attrs468: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_Attr_in_rule_Attrs472: new org.antlr.runtime.BitSet([0x00C00000, 0x00000000]),
    FOLLOW_23_in_rule_Attrs478: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_21_in_rule_AttrSpecs495: new org.antlr.runtime.BitSet([0x00800010, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_AttrSpecs500: new org.antlr.runtime.BitSet([0x00C00000, 0x00000000]),
    FOLLOW_22_in_rule_AttrSpecs503: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_AttrSpecs507: new org.antlr.runtime.BitSet([0x00C00000, 0x00000000]),
    FOLLOW_23_in_rule_AttrSpecs513: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_QualifiedNameWithWildCard532: new org.antlr.runtime.BitSet([0x01000002, 0x00000000]),
    FOLLOW_24_in_rule_QualifiedNameWithWildCard534: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_ID_in_rule_QualifiedName549: new org.antlr.runtime.BitSet([0x02000002, 0x00000000]),
    FOLLOW_25_in_rule_QualifiedName552: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_ID_in_rule_QualifiedName554: new org.antlr.runtime.BitSet([0x02000002, 0x00000000]),
    FOLLOW_26_in_rule_DataType567: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_27_in_rule_DataType573: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_28_in_rule_DataType579: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_29_in_rule_MsgStereo590: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_30_in_rule_MsgStereo596: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_31_in_rule_MsgStereo602: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_32_in_rule_MsgStereo608: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_33_in_rule_MsgStereo614: new org.antlr.runtime.BitSet([0x00000002, 0x00000000])
});

})();