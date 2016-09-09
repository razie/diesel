// $ANTLR 3.3 avr. 19, 2016 01:13:22 /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g 2016-09-02 09:48:43



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
    T__37: 37,
    T__38: 38,
    T__39: 39,
    T__40: 40,
    TEXT: 4,
    NEWLINE: 5,
    ID: 6,
    STRING: 7,
    INT: 8,
    COMMENT: 9,
    WS: 10
});

(function(){
// public class variables
var EOF= -1,
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
    T__37= 37,
    T__38= 38,
    T__39= 39,
    T__40= 40,
    TEXT= 4,
    NEWLINE= 5,
    ID= 6,
    STRING= 7,
    INT= 8,
    COMMENT= 9,
    WS= 10;

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

                if ( (LA1_0==TEXT||LA1_0==11||(LA1_0>=14 && LA1_0<=15)||(LA1_0>=17 && LA1_0<=18)||(LA1_0>=20 && LA1_0<=22)||LA1_0==28||LA1_0==30) ) {
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:28:1: rule_AbstractElement : ( rule_Expect | rule_Msg | rule_Match | rule_When | rule_Receive | rule_Option | rule_Val | rule_Mock | rule_Topic | rule_Braq | TEXT );
    // $ANTLR start "rule_AbstractElement"
    rule_AbstractElement: function() {
        var retval = new Nvp1Parser.rule_AbstractElement_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var TEXT12 = null;
         var rule_Expect2 = null;
         var rule_Msg3 = null;
         var rule_Match4 = null;
         var rule_When5 = null;
         var rule_Receive6 = null;
         var rule_Option7 = null;
         var rule_Val8 = null;
         var rule_Mock9 = null;
         var rule_Topic10 = null;
         var rule_Braq11 = null;

        var TEXT12_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:28:21: ( rule_Expect | rule_Msg | rule_Match | rule_When | rule_Receive | rule_Option | rule_Val | rule_Mock | rule_Topic | rule_Braq | TEXT )
            var alt2=11;
            switch ( this.input.LA(1) ) {
            case 20:
                alt2=1;
                break;
            case 14:
                alt2=2;
                break;
            case 17:
                alt2=3;
                break;
            case 15:
                alt2=4;
                break;
            case 11:
                alt2=5;
                break;
            case 22:
                alt2=6;
                break;
            case 21:
                alt2=7;
                break;
            case 18:
                alt2=8;
                break;
            case 28:
                alt2=9;
                break;
            case 30:
                alt2=10;
                break;
            case TEXT:
                alt2=11;
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
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:30: rule_Match
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Match_in_rule_AbstractElement93);
                    rule_Match4=this.rule_Match();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Match4.getTree());


                    break;
                case 4 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:43: rule_When
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_When_in_rule_AbstractElement97);
                    rule_When5=this.rule_When();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_When5.getTree());


                    break;
                case 5 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:55: rule_Receive
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Receive_in_rule_AbstractElement101);
                    rule_Receive6=this.rule_Receive();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Receive6.getTree());


                    break;
                case 6 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:70: rule_Option
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Option_in_rule_AbstractElement105);
                    rule_Option7=this.rule_Option();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Option7.getTree());


                    break;
                case 7 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:84: rule_Val
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Val_in_rule_AbstractElement109);
                    rule_Val8=this.rule_Val();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Val8.getTree());


                    break;
                case 8 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:95: rule_Mock
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Mock_in_rule_AbstractElement113);
                    rule_Mock9=this.rule_Mock();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Mock9.getTree());


                    break;
                case 9 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:107: rule_Topic
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Topic_in_rule_AbstractElement117);
                    rule_Topic10=this.rule_Topic();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Topic10.getTree());


                    break;
                case 10 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:120: rule_Braq
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Braq_in_rule_AbstractElement121);
                    rule_Braq11=this.rule_Braq();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Braq11.getTree());


                    break;
                case 11 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:132: TEXT
                    root_0 = this.adaptor.nil();

                    TEXT12=this.match(this.input,TEXT,Nvp1Parser.FOLLOW_TEXT_in_rule_AbstractElement125); 
                    TEXT12_tree = this.adaptor.create(TEXT12);
                    this.adaptor.addChild(root_0, TEXT12_tree);



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
    rule_Receive_return: (function() {
        Nvp1Parser.rule_Receive_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Receive_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:36:1: rule_Receive : '$receive' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? NEWLINE ;
    // $ANTLR start "rule_Receive"
    rule_Receive: function() {
        var retval = new Nvp1Parser.rule_Receive_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal13 = null;
        var char_literal14 = null;
        var char_literal15 = null;
        var NEWLINE16 = null;
         var stype = null;
         var name = null;
         var attrs = null;

        var string_literal13_tree=null;
        var char_literal14_tree=null;
        var char_literal15_tree=null;
        var NEWLINE16_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:36:13: ( '$receive' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:5: '$receive' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? NEWLINE
            root_0 = this.adaptor.nil();

            string_literal13=this.match(this.input,11,Nvp1Parser.FOLLOW_11_in_rule_Receive141); 
            string_literal13_tree = this.adaptor.create(string_literal13);
            this.adaptor.addChild(root_0, string_literal13_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:16: ( '<' stype= rule_MsgStereo '>' )?
            var alt3=2;
            var LA3_0 = this.input.LA(1);

            if ( (LA3_0==12) ) {
                alt3=1;
            }
            switch (alt3) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:17: '<' stype= rule_MsgStereo '>'
                    char_literal14=this.match(this.input,12,Nvp1Parser.FOLLOW_12_in_rule_Receive144); 
                    char_literal14_tree = this.adaptor.create(char_literal14);
                    this.adaptor.addChild(root_0, char_literal14_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_MsgStereo_in_rule_Receive148);
                    stype=this.rule_MsgStereo();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, stype.getTree());
                    char_literal15=this.match(this.input,13,Nvp1Parser.FOLLOW_13_in_rule_Receive150); 
                    char_literal15_tree = this.adaptor.create(char_literal15);
                    this.adaptor.addChild(root_0, char_literal15_tree);



                    break;

            }

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_Receive156);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:77: (attrs= rule_AttrSpecs )?
            var alt4=2;
            var LA4_0 = this.input.LA(1);

            if ( (LA4_0==25) ) {
                alt4=1;
            }
            switch (alt4) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:77: attrs= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Receive160);
                    attrs=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());


                    break;

            }

            NEWLINE16=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Receive163); 
            NEWLINE16_tree = this.adaptor.create(NEWLINE16);
            this.adaptor.addChild(root_0, NEWLINE16_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:43:1: rule_Msg : '$msg' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? NEWLINE ;
    // $ANTLR start "rule_Msg"
    rule_Msg: function() {
        var retval = new Nvp1Parser.rule_Msg_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal17 = null;
        var char_literal18 = null;
        var char_literal19 = null;
        var NEWLINE20 = null;
         var stype = null;
         var name = null;
         var attrs = null;

        var string_literal17_tree=null;
        var char_literal18_tree=null;
        var char_literal19_tree=null;
        var NEWLINE20_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:43:9: ( '$msg' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:44:5: '$msg' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? NEWLINE
            root_0 = this.adaptor.nil();

            string_literal17=this.match(this.input,14,Nvp1Parser.FOLLOW_14_in_rule_Msg178); 
            string_literal17_tree = this.adaptor.create(string_literal17);
            this.adaptor.addChild(root_0, string_literal17_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:44:12: ( '<' stype= rule_MsgStereo '>' )?
            var alt5=2;
            var LA5_0 = this.input.LA(1);

            if ( (LA5_0==12) ) {
                alt5=1;
            }
            switch (alt5) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:44:13: '<' stype= rule_MsgStereo '>'
                    char_literal18=this.match(this.input,12,Nvp1Parser.FOLLOW_12_in_rule_Msg181); 
                    char_literal18_tree = this.adaptor.create(char_literal18);
                    this.adaptor.addChild(root_0, char_literal18_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_MsgStereo_in_rule_Msg185);
                    stype=this.rule_MsgStereo();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, stype.getTree());
                    char_literal19=this.match(this.input,13,Nvp1Parser.FOLLOW_13_in_rule_Msg187); 
                    char_literal19_tree = this.adaptor.create(char_literal19);
                    this.adaptor.addChild(root_0, char_literal19_tree);



                    break;

            }

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_Msg193);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:44:73: (attrs= rule_AttrSpecs )?
            var alt6=2;
            var LA6_0 = this.input.LA(1);

            if ( (LA6_0==25) ) {
                alt6=1;
            }
            switch (alt6) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:44:73: attrs= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Msg197);
                    attrs=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());


                    break;

            }

            NEWLINE20=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Msg200); 
            NEWLINE20_tree = this.adaptor.create(NEWLINE20);
            this.adaptor.addChild(root_0, NEWLINE20_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:50:1: rule_When : '$when' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' z= ID (za= rule_AttrSpecs )? NEWLINE ;
    // $ANTLR start "rule_When"
    rule_When: function() {
        var retval = new Nvp1Parser.rule_When_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var a = null;
        var z = null;
        var string_literal21 = null;
        var string_literal22 = null;
        var NEWLINE23 = null;
         var aa = null;
         var cond = null;
         var za = null;

        var a_tree=null;
        var z_tree=null;
        var string_literal21_tree=null;
        var string_literal22_tree=null;
        var NEWLINE23_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:50:10: ( '$when' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' z= ID (za= rule_AttrSpecs )? NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:2: '$when' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' z= ID (za= rule_AttrSpecs )? NEWLINE
            root_0 = this.adaptor.nil();

            string_literal21=this.match(this.input,15,Nvp1Parser.FOLLOW_15_in_rule_When212); 
            string_literal21_tree = this.adaptor.create(string_literal21);
            this.adaptor.addChild(root_0, string_literal21_tree);

            a=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_When216); 
            a_tree = this.adaptor.create(a);
            this.adaptor.addChild(root_0, a_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:17: (aa= rule_Attrs )?
            var alt7=2;
            var LA7_0 = this.input.LA(1);

            if ( (LA7_0==25) ) {
                alt7=1;
            }
            switch (alt7) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:17: aa= rule_Attrs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attrs_in_rule_When220);
                    aa=this.rule_Attrs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, aa.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:34: (cond= rule_Condition )?
            var alt8=2;
            var LA8_0 = this.input.LA(1);

            if ( (LA8_0==19) ) {
                alt8=1;
            }
            switch (alt8) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:34: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_When225);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            string_literal22=this.match(this.input,16,Nvp1Parser.FOLLOW_16_in_rule_When228); 
            string_literal22_tree = this.adaptor.create(string_literal22);
            this.adaptor.addChild(root_0, string_literal22_tree);

            z=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_When232); 
            z_tree = this.adaptor.create(z);
            this.adaptor.addChild(root_0, z_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:63: (za= rule_AttrSpecs )?
            var alt9=2;
            var LA9_0 = this.input.LA(1);

            if ( (LA9_0==25) ) {
                alt9=1;
            }
            switch (alt9) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:63: za= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_When236);
                    za=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, za.getTree());


                    break;

            }

            NEWLINE23=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_When240); 
            NEWLINE23_tree = this.adaptor.create(NEWLINE23);
            this.adaptor.addChild(root_0, NEWLINE23_tree);




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
    rule_Match_return: (function() {
        Nvp1Parser.rule_Match_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Match_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:57:1: rule_Match : '$match' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? NEWLINE ;
    // $ANTLR start "rule_Match"
    rule_Match: function() {
        var retval = new Nvp1Parser.rule_Match_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var a = null;
        var string_literal24 = null;
        var NEWLINE25 = null;
         var aa = null;
         var cond = null;

        var a_tree=null;
        var string_literal24_tree=null;
        var NEWLINE25_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:57:11: ( '$match' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:58:2: '$match' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? NEWLINE
            root_0 = this.adaptor.nil();

            string_literal24=this.match(this.input,17,Nvp1Parser.FOLLOW_17_in_rule_Match252); 
            string_literal24_tree = this.adaptor.create(string_literal24);
            this.adaptor.addChild(root_0, string_literal24_tree);

            a=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_Match256); 
            a_tree = this.adaptor.create(a);
            this.adaptor.addChild(root_0, a_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:58:18: (aa= rule_Attrs )?
            var alt10=2;
            var LA10_0 = this.input.LA(1);

            if ( (LA10_0==25) ) {
                alt10=1;
            }
            switch (alt10) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:58:18: aa= rule_Attrs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attrs_in_rule_Match260);
                    aa=this.rule_Attrs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, aa.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:58:35: (cond= rule_Condition )?
            var alt11=2;
            var LA11_0 = this.input.LA(1);

            if ( (LA11_0==19) ) {
                alt11=1;
            }
            switch (alt11) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:58:35: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_Match265);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            NEWLINE25=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Match269); 
            NEWLINE25_tree = this.adaptor.create(NEWLINE25);
            this.adaptor.addChild(root_0, NEWLINE25_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:64:1: rule_Mock : '$mock' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' (za= rule_AttrSpecs )? NEWLINE ;
    // $ANTLR start "rule_Mock"
    rule_Mock: function() {
        var retval = new Nvp1Parser.rule_Mock_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var a = null;
        var string_literal26 = null;
        var string_literal27 = null;
        var NEWLINE28 = null;
         var aa = null;
         var cond = null;
         var za = null;

        var a_tree=null;
        var string_literal26_tree=null;
        var string_literal27_tree=null;
        var NEWLINE28_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:64:10: ( '$mock' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' (za= rule_AttrSpecs )? NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:2: '$mock' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' (za= rule_AttrSpecs )? NEWLINE
            root_0 = this.adaptor.nil();

            string_literal26=this.match(this.input,18,Nvp1Parser.FOLLOW_18_in_rule_Mock281); 
            string_literal26_tree = this.adaptor.create(string_literal26);
            this.adaptor.addChild(root_0, string_literal26_tree);

            a=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_Mock285); 
            a_tree = this.adaptor.create(a);
            this.adaptor.addChild(root_0, a_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:17: (aa= rule_Attrs )?
            var alt12=2;
            var LA12_0 = this.input.LA(1);

            if ( (LA12_0==25) ) {
                alt12=1;
            }
            switch (alt12) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:17: aa= rule_Attrs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attrs_in_rule_Mock289);
                    aa=this.rule_Attrs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, aa.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:34: (cond= rule_Condition )?
            var alt13=2;
            var LA13_0 = this.input.LA(1);

            if ( (LA13_0==19) ) {
                alt13=1;
            }
            switch (alt13) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:34: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_Mock294);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            string_literal27=this.match(this.input,16,Nvp1Parser.FOLLOW_16_in_rule_Mock297); 
            string_literal27_tree = this.adaptor.create(string_literal27);
            this.adaptor.addChild(root_0, string_literal27_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:58: (za= rule_AttrSpecs )?
            var alt14=2;
            var LA14_0 = this.input.LA(1);

            if ( (LA14_0==25) ) {
                alt14=1;
            }
            switch (alt14) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:58: za= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Mock301);
                    za=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, za.getTree());


                    break;

            }

            NEWLINE28=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Mock304); 
            NEWLINE28_tree = this.adaptor.create(NEWLINE28);
            this.adaptor.addChild(root_0, NEWLINE28_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:71:1: rule_Expect : ( rule_ExpectM | rule_ExpectV );
    // $ANTLR start "rule_Expect"
    rule_Expect: function() {
        var retval = new Nvp1Parser.rule_Expect_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

         var rule_ExpectM29 = null;
         var rule_ExpectV30 = null;


        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:71:12: ( rule_ExpectM | rule_ExpectV )
            var alt15=2;
            var LA15_0 = this.input.LA(1);

            if ( (LA15_0==20) ) {
                var LA15_1 = this.input.LA(2);

                if ( (LA15_1==14) ) {
                    alt15=1;
                }
                else if ( (LA15_1==21) ) {
                    alt15=2;
                }
                else {
                    var nvae =
                        new org.antlr.runtime.NoViableAltException("", 15, 1, this.input);

                    throw nvae;
                }
            }
            else {
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 15, 0, this.input);

                throw nvae;
            }
            switch (alt15) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:72:5: rule_ExpectM
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_ExpectM_in_rule_Expect319);
                    rule_ExpectM29=this.rule_ExpectM();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_ExpectM29.getTree());


                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:72:20: rule_ExpectV
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_ExpectV_in_rule_Expect323);
                    rule_ExpectV30=this.rule_ExpectV();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_ExpectV30.getTree());


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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:78:1: rule_Condition : '$if' attrs= rule_AttrSpecs ;
    // $ANTLR start "rule_Condition"
    rule_Condition: function() {
        var retval = new Nvp1Parser.rule_Condition_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal31 = null;
         var attrs = null;

        var string_literal31_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:78:15: ( '$if' attrs= rule_AttrSpecs )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:79:5: '$if' attrs= rule_AttrSpecs
            root_0 = this.adaptor.nil();

            string_literal31=this.match(this.input,19,Nvp1Parser.FOLLOW_19_in_rule_Condition338); 
            string_literal31_tree = this.adaptor.create(string_literal31);
            this.adaptor.addChild(root_0, string_literal31_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Condition342);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:85:1: rule_ExpectM : '$expect' ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? ) (cond= rule_Condition )? NEWLINE ;
    // $ANTLR start "rule_ExpectM"
    rule_ExpectM: function() {
        var retval = new Nvp1Parser.rule_ExpectM_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal32 = null;
        var string_literal33 = null;
        var NEWLINE34 = null;
         var name = null;
         var attrs = null;
         var cond = null;

        var string_literal32_tree=null;
        var string_literal33_tree=null;
        var NEWLINE34_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:85:13: ( '$expect' ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? ) (cond= rule_Condition )? NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:86:5: '$expect' ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? ) (cond= rule_Condition )? NEWLINE
            root_0 = this.adaptor.nil();

            string_literal32=this.match(this.input,20,Nvp1Parser.FOLLOW_20_in_rule_ExpectM357); 
            string_literal32_tree = this.adaptor.create(string_literal32);
            this.adaptor.addChild(root_0, string_literal32_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:86:15: ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:86:16: '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )?
            string_literal33=this.match(this.input,14,Nvp1Parser.FOLLOW_14_in_rule_ExpectM360); 
            string_literal33_tree = this.adaptor.create(string_literal33);
            this.adaptor.addChild(root_0, string_literal33_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_ExpectM364);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:86:52: (attrs= rule_AttrSpecs )?
            var alt16=2;
            var LA16_0 = this.input.LA(1);

            if ( (LA16_0==25) ) {
                alt16=1;
            }
            switch (alt16) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:86:52: attrs= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_ExpectM368);
                    attrs=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());


                    break;

            }




            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:86:70: (cond= rule_Condition )?
            var alt17=2;
            var LA17_0 = this.input.LA(1);

            if ( (LA17_0==19) ) {
                alt17=1;
            }
            switch (alt17) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:86:71: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_ExpectM375);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            NEWLINE34=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_ExpectM378); 
            NEWLINE34_tree = this.adaptor.create(NEWLINE34);
            this.adaptor.addChild(root_0, NEWLINE34_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:92:1: rule_ExpectV : '$expect' ( '$val' (p= rule_AttrSpec )? ) (cond= rule_Condition )? NEWLINE ;
    // $ANTLR start "rule_ExpectV"
    rule_ExpectV: function() {
        var retval = new Nvp1Parser.rule_ExpectV_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal35 = null;
        var string_literal36 = null;
        var NEWLINE37 = null;
         var p = null;
         var cond = null;

        var string_literal35_tree=null;
        var string_literal36_tree=null;
        var NEWLINE37_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:92:13: ( '$expect' ( '$val' (p= rule_AttrSpec )? ) (cond= rule_Condition )? NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:93:5: '$expect' ( '$val' (p= rule_AttrSpec )? ) (cond= rule_Condition )? NEWLINE
            root_0 = this.adaptor.nil();

            string_literal35=this.match(this.input,20,Nvp1Parser.FOLLOW_20_in_rule_ExpectV393); 
            string_literal35_tree = this.adaptor.create(string_literal35);
            this.adaptor.addChild(root_0, string_literal35_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:93:15: ( '$val' (p= rule_AttrSpec )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:93:16: '$val' (p= rule_AttrSpec )?
            string_literal36=this.match(this.input,21,Nvp1Parser.FOLLOW_21_in_rule_ExpectV396); 
            string_literal36_tree = this.adaptor.create(string_literal36);
            this.adaptor.addChild(root_0, string_literal36_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:93:24: (p= rule_AttrSpec )?
            var alt18=2;
            var LA18_0 = this.input.LA(1);

            if ( (LA18_0==ID) ) {
                alt18=1;
            }
            switch (alt18) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:93:24: p= rule_AttrSpec
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_ExpectV400);
                    p=this.rule_AttrSpec();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, p.getTree());


                    break;

            }




            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:93:41: (cond= rule_Condition )?
            var alt19=2;
            var LA19_0 = this.input.LA(1);

            if ( (LA19_0==19) ) {
                alt19=1;
            }
            switch (alt19) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:93:42: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_ExpectV407);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            NEWLINE37=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_ExpectV410); 
            NEWLINE37_tree = this.adaptor.create(NEWLINE37);
            this.adaptor.addChild(root_0, NEWLINE37_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:99:1: rule_Val : '$val' p= rule_AttrSpec NEWLINE ;
    // $ANTLR start "rule_Val"
    rule_Val: function() {
        var retval = new Nvp1Parser.rule_Val_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal38 = null;
        var NEWLINE39 = null;
         var p = null;

        var string_literal38_tree=null;
        var NEWLINE39_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:99:9: ( '$val' p= rule_AttrSpec NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:100:5: '$val' p= rule_AttrSpec NEWLINE
            root_0 = this.adaptor.nil();

            string_literal38=this.match(this.input,21,Nvp1Parser.FOLLOW_21_in_rule_Val425); 
            string_literal38_tree = this.adaptor.create(string_literal38);
            this.adaptor.addChild(root_0, string_literal38_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_Val429);
            p=this.rule_AttrSpec();

            this.state._fsp--;

            this.adaptor.addChild(root_0, p.getTree());
            NEWLINE39=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Val431); 
            NEWLINE39_tree = this.adaptor.create(NEWLINE39);
            this.adaptor.addChild(root_0, NEWLINE39_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:106:1: rule_Option : '$opt' attr= rule_AttrSpec NEWLINE ;
    // $ANTLR start "rule_Option"
    rule_Option: function() {
        var retval = new Nvp1Parser.rule_Option_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal40 = null;
        var NEWLINE41 = null;
         var attr = null;

        var string_literal40_tree=null;
        var NEWLINE41_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:106:12: ( '$opt' attr= rule_AttrSpec NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:107:5: '$opt' attr= rule_AttrSpec NEWLINE
            root_0 = this.adaptor.nil();

            string_literal40=this.match(this.input,22,Nvp1Parser.FOLLOW_22_in_rule_Option446); 
            string_literal40_tree = this.adaptor.create(string_literal40);
            this.adaptor.addChild(root_0, string_literal40_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_Option450);
            attr=this.rule_AttrSpec();

            this.state._fsp--;

            this.adaptor.addChild(root_0, attr.getTree());
            NEWLINE41=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Option452); 
            NEWLINE41_tree = this.adaptor.create(NEWLINE41);
            this.adaptor.addChild(root_0, NEWLINE41_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:113:1: rule_AttrSpec : name= rule_QualifiedName ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? ;
    // $ANTLR start "rule_AttrSpec"
    rule_AttrSpec: function() {
        var retval = new Nvp1Parser.rule_AttrSpec_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal42 = null;
        var char_literal43 = null;
         var name = null;
         var ttype = null;
         var eexpr = null;

        var char_literal42_tree=null;
        var char_literal43_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:113:14: (name= rule_QualifiedName ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:114:3: name= rule_QualifiedName ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )?
            root_0 = this.adaptor.nil();

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_AttrSpec467);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:114:27: ( ':' ttype= rule_DataType )?
            var alt20=2;
            var LA20_0 = this.input.LA(1);

            if ( (LA20_0==23) ) {
                alt20=1;
            }
            switch (alt20) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:114:28: ':' ttype= rule_DataType
                    char_literal42=this.match(this.input,23,Nvp1Parser.FOLLOW_23_in_rule_AttrSpec470); 
                    char_literal42_tree = this.adaptor.create(char_literal42);
                    this.adaptor.addChild(root_0, char_literal42_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_DataType_in_rule_AttrSpec474);
                    ttype=this.rule_DataType();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, ttype.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:114:54: ( '=' eexpr= rule_EXPR )?
            var alt21=2;
            var LA21_0 = this.input.LA(1);

            if ( (LA21_0==24) ) {
                alt21=1;
            }
            switch (alt21) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:114:55: '=' eexpr= rule_EXPR
                    char_literal43=this.match(this.input,24,Nvp1Parser.FOLLOW_24_in_rule_AttrSpec479); 
                    char_literal43_tree = this.adaptor.create(char_literal43);
                    this.adaptor.addChild(root_0, char_literal43_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_EXPR_in_rule_AttrSpec483);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:119:1: rule_Attr : name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? ;
    // $ANTLR start "rule_Attr"
    rule_Attr: function() {
        var retval = new Nvp1Parser.rule_Attr_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var name = null;
        var char_literal44 = null;
        var char_literal45 = null;
         var ttype = null;
         var eexpr = null;

        var name_tree=null;
        var char_literal44_tree=null;
        var char_literal45_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:119:10: (name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:120:3: name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )?
            root_0 = this.adaptor.nil();

            name=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_Attr499); 
            name_tree = this.adaptor.create(name);
            this.adaptor.addChild(root_0, name_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:120:11: ( ':' ttype= rule_DataType )?
            var alt22=2;
            var LA22_0 = this.input.LA(1);

            if ( (LA22_0==23) ) {
                alt22=1;
            }
            switch (alt22) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:120:12: ':' ttype= rule_DataType
                    char_literal44=this.match(this.input,23,Nvp1Parser.FOLLOW_23_in_rule_Attr502); 
                    char_literal44_tree = this.adaptor.create(char_literal44);
                    this.adaptor.addChild(root_0, char_literal44_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_DataType_in_rule_Attr506);
                    ttype=this.rule_DataType();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, ttype.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:120:38: ( '=' eexpr= rule_EXPR )?
            var alt23=2;
            var LA23_0 = this.input.LA(1);

            if ( (LA23_0==24) ) {
                alt23=1;
            }
            switch (alt23) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:120:39: '=' eexpr= rule_EXPR
                    char_literal45=this.match(this.input,24,Nvp1Parser.FOLLOW_24_in_rule_Attr511); 
                    char_literal45_tree = this.adaptor.create(char_literal45);
                    this.adaptor.addChild(root_0, char_literal45_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_EXPR_in_rule_Attr515);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:125:1: rule_EXPR : (parm= rule_QualifiedName | svalue= STRING | ivalue= INT );
    // $ANTLR start "rule_EXPR"
    rule_EXPR: function() {
        var retval = new Nvp1Parser.rule_EXPR_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var svalue = null;
        var ivalue = null;
         var parm = null;

        var svalue_tree=null;
        var ivalue_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:125:10: (parm= rule_QualifiedName | svalue= STRING | ivalue= INT )
            var alt24=3;
            switch ( this.input.LA(1) ) {
            case ID:
                alt24=1;
                break;
            case STRING:
                alt24=2;
                break;
            case INT:
                alt24=3;
                break;
            default:
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 24, 0, this.input);

                throw nvae;
            }

            switch (alt24) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:126:3: parm= rule_QualifiedName
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_EXPR531);
                    parm=this.rule_QualifiedName();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, parm.getTree());


                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:126:29: svalue= STRING
                    root_0 = this.adaptor.nil();

                    svalue=this.match(this.input,STRING,Nvp1Parser.FOLLOW_STRING_in_rule_EXPR537); 
                    svalue_tree = this.adaptor.create(svalue);
                    this.adaptor.addChild(root_0, svalue_tree);



                    break;
                case 3 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:126:45: ivalue= INT
                    root_0 = this.adaptor.nil();

                    ivalue=this.match(this.input,INT,Nvp1Parser.FOLLOW_INT_in_rule_EXPR543); 
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:132:1: rule_Attrs : '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')' ;
    // $ANTLR start "rule_Attrs"
    rule_Attrs: function() {
        var retval = new Nvp1Parser.rule_Attrs_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal46 = null;
        var char_literal47 = null;
        var char_literal48 = null;
        var list_attrs=null;
        var attrs = null;
        var char_literal46_tree=null;
        var char_literal47_tree=null;
        var char_literal48_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:132:11: ( '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:133:5: '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')'
            root_0 = this.adaptor.nil();

            char_literal46=this.match(this.input,25,Nvp1Parser.FOLLOW_25_in_rule_Attrs560); 
            char_literal46_tree = this.adaptor.create(char_literal46);
            this.adaptor.addChild(root_0, char_literal46_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:133:9: (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )?
            var alt26=2;
            var LA26_0 = this.input.LA(1);

            if ( (LA26_0==ID) ) {
                alt26=1;
            }
            switch (alt26) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:133:10: attrs+= rule_Attr ( ',' attrs+= rule_Attr )*
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attr_in_rule_Attrs565);
                    attrs=this.rule_Attr();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());
                    if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                    list_attrs.push(attrs.getTree());

                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:133:27: ( ',' attrs+= rule_Attr )*
                    loop25:
                    do {
                        var alt25=2;
                        var LA25_0 = this.input.LA(1);

                        if ( (LA25_0==26) ) {
                            alt25=1;
                        }


                        switch (alt25) {
                        case 1 :
                            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:133:28: ',' attrs+= rule_Attr
                            char_literal47=this.match(this.input,26,Nvp1Parser.FOLLOW_26_in_rule_Attrs568); 
                            char_literal47_tree = this.adaptor.create(char_literal47);
                            this.adaptor.addChild(root_0, char_literal47_tree);

                            this.pushFollow(Nvp1Parser.FOLLOW_rule_Attr_in_rule_Attrs572);
                            attrs=this.rule_Attr();

                            this.state._fsp--;

                            this.adaptor.addChild(root_0, attrs.getTree());
                            if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                            list_attrs.push(attrs.getTree());



                            break;

                        default :
                            break loop25;
                        }
                    } while (true);



                    break;

            }

            char_literal48=this.match(this.input,27,Nvp1Parser.FOLLOW_27_in_rule_Attrs578); 
            char_literal48_tree = this.adaptor.create(char_literal48);
            this.adaptor.addChild(root_0, char_literal48_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:139:1: rule_AttrSpecs : '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')' ;
    // $ANTLR start "rule_AttrSpecs"
    rule_AttrSpecs: function() {
        var retval = new Nvp1Parser.rule_AttrSpecs_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal49 = null;
        var char_literal50 = null;
        var char_literal51 = null;
        var list_attrs=null;
        var attrs = null;
        var char_literal49_tree=null;
        var char_literal50_tree=null;
        var char_literal51_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:139:15: ( '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:140:4: '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')'
            root_0 = this.adaptor.nil();

            char_literal49=this.match(this.input,25,Nvp1Parser.FOLLOW_25_in_rule_AttrSpecs592); 
            char_literal49_tree = this.adaptor.create(char_literal49);
            this.adaptor.addChild(root_0, char_literal49_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:140:8: (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )?
            var alt28=2;
            var LA28_0 = this.input.LA(1);

            if ( (LA28_0==ID) ) {
                alt28=1;
            }
            switch (alt28) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:140:9: attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )*
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_AttrSpecs597);
                    attrs=this.rule_AttrSpec();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());
                    if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                    list_attrs.push(attrs.getTree());

                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:140:30: ( ',' attrs+= rule_AttrSpec )*
                    loop27:
                    do {
                        var alt27=2;
                        var LA27_0 = this.input.LA(1);

                        if ( (LA27_0==26) ) {
                            alt27=1;
                        }


                        switch (alt27) {
                        case 1 :
                            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:140:31: ',' attrs+= rule_AttrSpec
                            char_literal50=this.match(this.input,26,Nvp1Parser.FOLLOW_26_in_rule_AttrSpecs600); 
                            char_literal50_tree = this.adaptor.create(char_literal50);
                            this.adaptor.addChild(root_0, char_literal50_tree);

                            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_AttrSpecs604);
                            attrs=this.rule_AttrSpec();

                            this.state._fsp--;

                            this.adaptor.addChild(root_0, attrs.getTree());
                            if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                            list_attrs.push(attrs.getTree());



                            break;

                        default :
                            break loop27;
                        }
                    } while (true);



                    break;

            }

            char_literal51=this.match(this.input,27,Nvp1Parser.FOLLOW_27_in_rule_AttrSpecs610); 
            char_literal51_tree = this.adaptor.create(char_literal51);
            this.adaptor.addChild(root_0, char_literal51_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:146:1: rule_Topic : '[[' name= rule_QualifiedName ( ':' t= rule_QualifiedName )? ']]' ;
    // $ANTLR start "rule_Topic"
    rule_Topic: function() {
        var retval = new Nvp1Parser.rule_Topic_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal52 = null;
        var char_literal53 = null;
        var string_literal54 = null;
         var name = null;
         var t = null;

        var string_literal52_tree=null;
        var char_literal53_tree=null;
        var string_literal54_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:146:11: ( '[[' name= rule_QualifiedName ( ':' t= rule_QualifiedName )? ']]' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:147:5: '[[' name= rule_QualifiedName ( ':' t= rule_QualifiedName )? ']]'
            root_0 = this.adaptor.nil();

            string_literal52=this.match(this.input,28,Nvp1Parser.FOLLOW_28_in_rule_Topic625); 
            string_literal52_tree = this.adaptor.create(string_literal52);
            this.adaptor.addChild(root_0, string_literal52_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_Topic629);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:147:34: ( ':' t= rule_QualifiedName )?
            var alt29=2;
            var LA29_0 = this.input.LA(1);

            if ( (LA29_0==23) ) {
                alt29=1;
            }
            switch (alt29) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:147:35: ':' t= rule_QualifiedName
                    char_literal53=this.match(this.input,23,Nvp1Parser.FOLLOW_23_in_rule_Topic632); 
                    char_literal53_tree = this.adaptor.create(char_literal53);
                    this.adaptor.addChild(root_0, char_literal53_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_Topic636);
                    t=this.rule_QualifiedName();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, t.getTree());


                    break;

            }

            string_literal54=this.match(this.input,29,Nvp1Parser.FOLLOW_29_in_rule_Topic640); 
            string_literal54_tree = this.adaptor.create(string_literal54);
            this.adaptor.addChild(root_0, string_literal54_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:153:1: rule_Braq : '}' ;
    // $ANTLR start "rule_Braq"
    rule_Braq: function() {
        var retval = new Nvp1Parser.rule_Braq_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal55 = null;

        var char_literal55_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:153:10: ( '}' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:154:5: '}'
            root_0 = this.adaptor.nil();

            char_literal55=this.match(this.input,30,Nvp1Parser.FOLLOW_30_in_rule_Braq655); 
            char_literal55_tree = this.adaptor.create(char_literal55);
            this.adaptor.addChild(root_0, char_literal55_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:163:1: rule_QualifiedNameWithWildCard : rule_QualifiedName ( '.*' )? ;
    // $ANTLR start "rule_QualifiedNameWithWildCard"
    rule_QualifiedNameWithWildCard: function() {
        var retval = new Nvp1Parser.rule_QualifiedNameWithWildCard_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal57 = null;
         var rule_QualifiedName56 = null;

        var string_literal57_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:163:31: ( rule_QualifiedName ( '.*' )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:164:5: rule_QualifiedName ( '.*' )?
            root_0 = this.adaptor.nil();

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_QualifiedNameWithWildCard673);
            rule_QualifiedName56=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, rule_QualifiedName56.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:164:24: ( '.*' )?
            var alt30=2;
            var LA30_0 = this.input.LA(1);

            if ( (LA30_0==31) ) {
                alt30=1;
            }
            switch (alt30) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:164:24: '.*'
                    string_literal57=this.match(this.input,31,Nvp1Parser.FOLLOW_31_in_rule_QualifiedNameWithWildCard675); 
                    string_literal57_tree = this.adaptor.create(string_literal57);
                    this.adaptor.addChild(root_0, string_literal57_tree);



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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:169:1: rule_QualifiedName : ID ( '.' ID )* ;
    // $ANTLR start "rule_QualifiedName"
    rule_QualifiedName: function() {
        var retval = new Nvp1Parser.rule_QualifiedName_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var ID58 = null;
        var char_literal59 = null;
        var ID60 = null;

        var ID58_tree=null;
        var char_literal59_tree=null;
        var ID60_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:169:19: ( ID ( '.' ID )* )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:170:5: ID ( '.' ID )*
            root_0 = this.adaptor.nil();

            ID58=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_QualifiedName690); 
            ID58_tree = this.adaptor.create(ID58);
            this.adaptor.addChild(root_0, ID58_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:170:8: ( '.' ID )*
            loop31:
            do {
                var alt31=2;
                var LA31_0 = this.input.LA(1);

                if ( (LA31_0==32) ) {
                    alt31=1;
                }


                switch (alt31) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:170:9: '.' ID
                    char_literal59=this.match(this.input,32,Nvp1Parser.FOLLOW_32_in_rule_QualifiedName693); 
                    char_literal59_tree = this.adaptor.create(char_literal59);
                    this.adaptor.addChild(root_0, char_literal59_tree);

                    ID60=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_QualifiedName695); 
                    ID60_tree = this.adaptor.create(ID60);
                    this.adaptor.addChild(root_0, ID60_tree);



                    break;

                default :
                    break loop31;
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:173:1: rule_DataType : (string= 'String' | int= 'Int' | date= 'Date' );
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
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:173:14: (string= 'String' | int= 'Int' | date= 'Date' )
            var alt32=3;
            switch ( this.input.LA(1) ) {
            case 33:
                alt32=1;
                break;
            case 34:
                alt32=2;
                break;
            case 35:
                alt32=3;
                break;
            default:
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 32, 0, this.input);

                throw nvae;
            }

            switch (alt32) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:174:2: string= 'String'
                    root_0 = this.adaptor.nil();

                    string=this.match(this.input,33,Nvp1Parser.FOLLOW_33_in_rule_DataType708); 
                    string_tree = this.adaptor.create(string);
                    this.adaptor.addChild(root_0, string_tree);



                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:174:20: int= 'Int'
                    root_0 = this.adaptor.nil();

                    int=this.match(this.input,34,Nvp1Parser.FOLLOW_34_in_rule_DataType714); 
                    int_tree = this.adaptor.create(int);
                    this.adaptor.addChild(root_0, int_tree);



                    break;
                case 3 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:174:32: date= 'Date'
                    root_0 = this.adaptor.nil();

                    date=this.match(this.input,35,Nvp1Parser.FOLLOW_35_in_rule_DataType720); 
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:177:1: rule_MsgStereo : (gET= 'GET' | pOST= 'POST' | camel= 'Camel' | jS= 'JS' | java= 'Java' );
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
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:177:15: (gET= 'GET' | pOST= 'POST' | camel= 'Camel' | jS= 'JS' | java= 'Java' )
            var alt33=5;
            switch ( this.input.LA(1) ) {
            case 36:
                alt33=1;
                break;
            case 37:
                alt33=2;
                break;
            case 38:
                alt33=3;
                break;
            case 39:
                alt33=4;
                break;
            case 40:
                alt33=5;
                break;
            default:
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 33, 0, this.input);

                throw nvae;
            }

            switch (alt33) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:178:2: gET= 'GET'
                    root_0 = this.adaptor.nil();

                    gET=this.match(this.input,36,Nvp1Parser.FOLLOW_36_in_rule_MsgStereo731); 
                    gET_tree = this.adaptor.create(gET);
                    this.adaptor.addChild(root_0, gET_tree);



                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:178:14: pOST= 'POST'
                    root_0 = this.adaptor.nil();

                    pOST=this.match(this.input,37,Nvp1Parser.FOLLOW_37_in_rule_MsgStereo737); 
                    pOST_tree = this.adaptor.create(pOST);
                    this.adaptor.addChild(root_0, pOST_tree);



                    break;
                case 3 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:178:28: camel= 'Camel'
                    root_0 = this.adaptor.nil();

                    camel=this.match(this.input,38,Nvp1Parser.FOLLOW_38_in_rule_MsgStereo743); 
                    camel_tree = this.adaptor.create(camel);
                    this.adaptor.addChild(root_0, camel_tree);



                    break;
                case 4 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:178:44: jS= 'JS'
                    root_0 = this.adaptor.nil();

                    jS=this.match(this.input,39,Nvp1Parser.FOLLOW_39_in_rule_MsgStereo749); 
                    jS_tree = this.adaptor.create(jS);
                    this.adaptor.addChild(root_0, jS_tree);



                    break;
                case 5 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:178:54: java= 'Java'
                    root_0 = this.adaptor.nil();

                    java=this.match(this.input,40,Nvp1Parser.FOLLOW_40_in_rule_MsgStereo755); 
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
    tokenNames: ["<invalid>", "<EOR>", "<DOWN>", "<UP>", "TEXT", "NEWLINE", "ID", "STRING", "INT", "COMMENT", "WS", "'$receive'", "'<'", "'>'", "'$msg'", "'$when'", "'=>'", "'$match'", "'$mock'", "'$if'", "'$expect'", "'$val'", "'$opt'", "':'", "'='", "'('", "','", "')'", "'[['", "']]'", "'}'", "'.*'", "'.'", "'String'", "'Int'", "'Date'", "'GET'", "'POST'", "'Camel'", "'JS'", "'Java'"],
    FOLLOW_rule_AbstractElement_in_rule_DomainModel67: new org.antlr.runtime.BitSet([0x5076C810, 0x00000000]),
    FOLLOW_EOF_in_rule_DomainModel70: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Expect_in_rule_AbstractElement85: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Msg_in_rule_AbstractElement89: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Match_in_rule_AbstractElement93: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_When_in_rule_AbstractElement97: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Receive_in_rule_AbstractElement101: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Option_in_rule_AbstractElement105: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Val_in_rule_AbstractElement109: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Mock_in_rule_AbstractElement113: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Topic_in_rule_AbstractElement117: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Braq_in_rule_AbstractElement121: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_TEXT_in_rule_AbstractElement125: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_11_in_rule_Receive141: new org.antlr.runtime.BitSet([0x00001040, 0x00000000]),
    FOLLOW_12_in_rule_Receive144: new org.antlr.runtime.BitSet([0x00000000, 0x000001F0]),
    FOLLOW_rule_MsgStereo_in_rule_Receive148: new org.antlr.runtime.BitSet([0x00002000, 0x00000000]),
    FOLLOW_13_in_rule_Receive150: new org.antlr.runtime.BitSet([0x00001040, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_Receive156: new org.antlr.runtime.BitSet([0x02000020, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Receive160: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Receive163: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_14_in_rule_Msg178: new org.antlr.runtime.BitSet([0x00001040, 0x00000000]),
    FOLLOW_12_in_rule_Msg181: new org.antlr.runtime.BitSet([0x00000000, 0x000001F0]),
    FOLLOW_rule_MsgStereo_in_rule_Msg185: new org.antlr.runtime.BitSet([0x00002000, 0x00000000]),
    FOLLOW_13_in_rule_Msg187: new org.antlr.runtime.BitSet([0x00001040, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_Msg193: new org.antlr.runtime.BitSet([0x02000020, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Msg197: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Msg200: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_15_in_rule_When212: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_ID_in_rule_When216: new org.antlr.runtime.BitSet([0x02090000, 0x00000000]),
    FOLLOW_rule_Attrs_in_rule_When220: new org.antlr.runtime.BitSet([0x00090000, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_When225: new org.antlr.runtime.BitSet([0x00010000, 0x00000000]),
    FOLLOW_16_in_rule_When228: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_ID_in_rule_When232: new org.antlr.runtime.BitSet([0x02000020, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_When236: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_When240: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_17_in_rule_Match252: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_ID_in_rule_Match256: new org.antlr.runtime.BitSet([0x02080020, 0x00000000]),
    FOLLOW_rule_Attrs_in_rule_Match260: new org.antlr.runtime.BitSet([0x00080020, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_Match265: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Match269: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_18_in_rule_Mock281: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_ID_in_rule_Mock285: new org.antlr.runtime.BitSet([0x02090000, 0x00000000]),
    FOLLOW_rule_Attrs_in_rule_Mock289: new org.antlr.runtime.BitSet([0x00090000, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_Mock294: new org.antlr.runtime.BitSet([0x00010000, 0x00000000]),
    FOLLOW_16_in_rule_Mock297: new org.antlr.runtime.BitSet([0x02000020, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Mock301: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Mock304: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_ExpectM_in_rule_Expect319: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_ExpectV_in_rule_Expect323: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_19_in_rule_Condition338: new org.antlr.runtime.BitSet([0x02000000, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Condition342: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_20_in_rule_ExpectM357: new org.antlr.runtime.BitSet([0x00004000, 0x00000000]),
    FOLLOW_14_in_rule_ExpectM360: new org.antlr.runtime.BitSet([0x00001040, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_ExpectM364: new org.antlr.runtime.BitSet([0x02080020, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_ExpectM368: new org.antlr.runtime.BitSet([0x00080020, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_ExpectM375: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_ExpectM378: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_20_in_rule_ExpectV393: new org.antlr.runtime.BitSet([0x00200000, 0x00000000]),
    FOLLOW_21_in_rule_ExpectV396: new org.antlr.runtime.BitSet([0x00081060, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_ExpectV400: new org.antlr.runtime.BitSet([0x00080020, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_ExpectV407: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_ExpectV410: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_21_in_rule_Val425: new org.antlr.runtime.BitSet([0x00001040, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_Val429: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Val431: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_22_in_rule_Option446: new org.antlr.runtime.BitSet([0x00001040, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_Option450: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Option452: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_AttrSpec467: new org.antlr.runtime.BitSet([0x01800002, 0x00000000]),
    FOLLOW_23_in_rule_AttrSpec470: new org.antlr.runtime.BitSet([0x00000000, 0x0000000E]),
    FOLLOW_rule_DataType_in_rule_AttrSpec474: new org.antlr.runtime.BitSet([0x01000002, 0x00000000]),
    FOLLOW_24_in_rule_AttrSpec479: new org.antlr.runtime.BitSet([0x000011C0, 0x00000000]),
    FOLLOW_rule_EXPR_in_rule_AttrSpec483: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_ID_in_rule_Attr499: new org.antlr.runtime.BitSet([0x01800002, 0x00000000]),
    FOLLOW_23_in_rule_Attr502: new org.antlr.runtime.BitSet([0x00000000, 0x0000000E]),
    FOLLOW_rule_DataType_in_rule_Attr506: new org.antlr.runtime.BitSet([0x01000002, 0x00000000]),
    FOLLOW_24_in_rule_Attr511: new org.antlr.runtime.BitSet([0x000011C0, 0x00000000]),
    FOLLOW_rule_EXPR_in_rule_Attr515: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_EXPR531: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_STRING_in_rule_EXPR537: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_INT_in_rule_EXPR543: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_25_in_rule_Attrs560: new org.antlr.runtime.BitSet([0x08000040, 0x00000000]),
    FOLLOW_rule_Attr_in_rule_Attrs565: new org.antlr.runtime.BitSet([0x0C000000, 0x00000000]),
    FOLLOW_26_in_rule_Attrs568: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_rule_Attr_in_rule_Attrs572: new org.antlr.runtime.BitSet([0x0C000000, 0x00000000]),
    FOLLOW_27_in_rule_Attrs578: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_25_in_rule_AttrSpecs592: new org.antlr.runtime.BitSet([0x08001040, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_AttrSpecs597: new org.antlr.runtime.BitSet([0x0C000000, 0x00000000]),
    FOLLOW_26_in_rule_AttrSpecs600: new org.antlr.runtime.BitSet([0x00001040, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_AttrSpecs604: new org.antlr.runtime.BitSet([0x0C000000, 0x00000000]),
    FOLLOW_27_in_rule_AttrSpecs610: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_28_in_rule_Topic625: new org.antlr.runtime.BitSet([0x00001040, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_Topic629: new org.antlr.runtime.BitSet([0x20800000, 0x00000000]),
    FOLLOW_23_in_rule_Topic632: new org.antlr.runtime.BitSet([0x00001040, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_Topic636: new org.antlr.runtime.BitSet([0x20000000, 0x00000000]),
    FOLLOW_29_in_rule_Topic640: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_30_in_rule_Braq655: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_QualifiedNameWithWildCard673: new org.antlr.runtime.BitSet([0x80000002, 0x00000000]),
    FOLLOW_31_in_rule_QualifiedNameWithWildCard675: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_ID_in_rule_QualifiedName690: new org.antlr.runtime.BitSet([0x00000002, 0x00000001]),
    FOLLOW_32_in_rule_QualifiedName693: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_ID_in_rule_QualifiedName695: new org.antlr.runtime.BitSet([0x00000002, 0x00000001]),
    FOLLOW_33_in_rule_DataType708: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_34_in_rule_DataType714: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_35_in_rule_DataType720: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_36_in_rule_MsgStereo731: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_37_in_rule_MsgStereo737: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_38_in_rule_MsgStereo743: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_39_in_rule_MsgStereo749: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_40_in_rule_MsgStereo755: new org.antlr.runtime.BitSet([0x00000002, 0x00000000])
});

})();