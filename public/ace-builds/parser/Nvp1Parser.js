// $ANTLR 3.3 avr. 19, 2016 01:13:22 /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g 2017-04-04 11:12:42



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
    T__41: 41,
    T__42: 42,
    T__43: 43,
    T__44: 44,
    TEXT: 4,
    NEWLINE: 5,
    ID: 6,
    ARROW: 7,
    STRING: 8,
    INT: 9,
    COMMENT: 10,
    WS: 11
});

(function(){
// public class variables
var EOF= -1,
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
    T__41= 41,
    T__42= 42,
    T__43= 43,
    T__44= 44,
    TEXT= 4,
    NEWLINE= 5,
    ID= 6,
    ARROW= 7,
    STRING= 8,
    INT= 9,
    COMMENT= 10,
    WS= 11;

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

                if ( (LA1_0==TEXT||LA1_0==12||(LA1_0>=15 && LA1_0<=18)||LA1_0==20||(LA1_0>=26 && LA1_0<=28)||LA1_0==32||LA1_0==34) ) {
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:28:1: rule_AbstractElement : ( rule_Expect | rule_Msg | rule_Match | rule_When | rule_Receive | rule_Flow | rule_Option | rule_Val | rule_Mock | rule_Topic | rule_Braq | TEXT );
    // $ANTLR start "rule_AbstractElement"
    rule_AbstractElement: function() {
        var retval = new Nvp1Parser.rule_AbstractElement_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var TEXT13 = null;
         var rule_Expect2 = null;
         var rule_Msg3 = null;
         var rule_Match4 = null;
         var rule_When5 = null;
         var rule_Receive6 = null;
         var rule_Flow7 = null;
         var rule_Option8 = null;
         var rule_Val9 = null;
         var rule_Mock10 = null;
         var rule_Topic11 = null;
         var rule_Braq12 = null;

        var TEXT13_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:28:21: ( rule_Expect | rule_Msg | rule_Match | rule_When | rule_Receive | rule_Flow | rule_Option | rule_Val | rule_Mock | rule_Topic | rule_Braq | TEXT )
            var alt2=12;
            switch ( this.input.LA(1) ) {
            case 26:
                alt2=1;
                break;
            case 15:
                alt2=2;
                break;
            case 17:
                alt2=3;
                break;
            case 16:
                alt2=4;
                break;
            case 12:
                alt2=5;
                break;
            case 20:
                alt2=6;
                break;
            case 28:
                alt2=7;
                break;
            case 27:
                alt2=8;
                break;
            case 18:
                alt2=9;
                break;
            case 32:
                alt2=10;
                break;
            case 34:
                alt2=11;
                break;
            case TEXT:
                alt2=12;
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
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:70: rule_Flow
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Flow_in_rule_AbstractElement105);
                    rule_Flow7=this.rule_Flow();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Flow7.getTree());


                    break;
                case 7 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:82: rule_Option
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Option_in_rule_AbstractElement109);
                    rule_Option8=this.rule_Option();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Option8.getTree());


                    break;
                case 8 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:96: rule_Val
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Val_in_rule_AbstractElement113);
                    rule_Val9=this.rule_Val();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Val9.getTree());


                    break;
                case 9 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:107: rule_Mock
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Mock_in_rule_AbstractElement117);
                    rule_Mock10=this.rule_Mock();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Mock10.getTree());


                    break;
                case 10 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:119: rule_Topic
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Topic_in_rule_AbstractElement121);
                    rule_Topic11=this.rule_Topic();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Topic11.getTree());


                    break;
                case 11 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:132: rule_Braq
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Braq_in_rule_AbstractElement125);
                    rule_Braq12=this.rule_Braq();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_Braq12.getTree());


                    break;
                case 12 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:29:144: TEXT
                    root_0 = this.adaptor.nil();

                    TEXT13=this.match(this.input,TEXT,Nvp1Parser.FOLLOW_TEXT_in_rule_AbstractElement129); 
                    TEXT13_tree = this.adaptor.create(TEXT13);
                    this.adaptor.addChild(root_0, TEXT13_tree);



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

        var string_literal14 = null;
        var char_literal15 = null;
        var char_literal16 = null;
        var NEWLINE17 = null;
         var stype = null;
         var name = null;
         var attrs = null;

        var string_literal14_tree=null;
        var char_literal15_tree=null;
        var char_literal16_tree=null;
        var NEWLINE17_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:36:13: ( '$receive' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:5: '$receive' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? NEWLINE
            root_0 = this.adaptor.nil();

            string_literal14=this.match(this.input,12,Nvp1Parser.FOLLOW_12_in_rule_Receive145); 
            string_literal14_tree = this.adaptor.create(string_literal14);
            this.adaptor.addChild(root_0, string_literal14_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:16: ( '<' stype= rule_MsgStereo '>' )?
            var alt3=2;
            var LA3_0 = this.input.LA(1);

            if ( (LA3_0==13) ) {
                alt3=1;
            }
            switch (alt3) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:17: '<' stype= rule_MsgStereo '>'
                    char_literal15=this.match(this.input,13,Nvp1Parser.FOLLOW_13_in_rule_Receive148); 
                    char_literal15_tree = this.adaptor.create(char_literal15);
                    this.adaptor.addChild(root_0, char_literal15_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_MsgStereo_in_rule_Receive152);
                    stype=this.rule_MsgStereo();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, stype.getTree());
                    char_literal16=this.match(this.input,14,Nvp1Parser.FOLLOW_14_in_rule_Receive154); 
                    char_literal16_tree = this.adaptor.create(char_literal16);
                    this.adaptor.addChild(root_0, char_literal16_tree);



                    break;

            }

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_Receive160);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:77: (attrs= rule_AttrSpecs )?
            var alt4=2;
            var LA4_0 = this.input.LA(1);

            if ( (LA4_0==23) ) {
                alt4=1;
            }
            switch (alt4) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:37:77: attrs= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Receive164);
                    attrs=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());


                    break;

            }

            NEWLINE17=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Receive167); 
            NEWLINE17_tree = this.adaptor.create(NEWLINE17);
            this.adaptor.addChild(root_0, NEWLINE17_tree);




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

        var string_literal18 = null;
        var char_literal19 = null;
        var char_literal20 = null;
        var NEWLINE21 = null;
         var stype = null;
         var name = null;
         var attrs = null;

        var string_literal18_tree=null;
        var char_literal19_tree=null;
        var char_literal20_tree=null;
        var NEWLINE21_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:43:9: ( '$msg' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:44:5: '$msg' ( '<' stype= rule_MsgStereo '>' )? name= rule_QualifiedName (attrs= rule_AttrSpecs )? NEWLINE
            root_0 = this.adaptor.nil();

            string_literal18=this.match(this.input,15,Nvp1Parser.FOLLOW_15_in_rule_Msg182); 
            string_literal18_tree = this.adaptor.create(string_literal18);
            this.adaptor.addChild(root_0, string_literal18_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:44:12: ( '<' stype= rule_MsgStereo '>' )?
            var alt5=2;
            var LA5_0 = this.input.LA(1);

            if ( (LA5_0==13) ) {
                alt5=1;
            }
            switch (alt5) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:44:13: '<' stype= rule_MsgStereo '>'
                    char_literal19=this.match(this.input,13,Nvp1Parser.FOLLOW_13_in_rule_Msg185); 
                    char_literal19_tree = this.adaptor.create(char_literal19);
                    this.adaptor.addChild(root_0, char_literal19_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_MsgStereo_in_rule_Msg189);
                    stype=this.rule_MsgStereo();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, stype.getTree());
                    char_literal20=this.match(this.input,14,Nvp1Parser.FOLLOW_14_in_rule_Msg191); 
                    char_literal20_tree = this.adaptor.create(char_literal20);
                    this.adaptor.addChild(root_0, char_literal20_tree);



                    break;

            }

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_Msg197);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:44:73: (attrs= rule_AttrSpecs )?
            var alt6=2;
            var LA6_0 = this.input.LA(1);

            if ( (LA6_0==23) ) {
                alt6=1;
            }
            switch (alt6) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:44:73: attrs= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Msg201);
                    attrs=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());


                    break;

            }

            NEWLINE21=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Msg204); 
            NEWLINE21_tree = this.adaptor.create(NEWLINE21);
            this.adaptor.addChild(root_0, NEWLINE21_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:50:1: rule_When : '$when' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? ARROW z= ID (za= rule_AttrSpecs )? NEWLINE ( ARROW z= ID (za= rule_AttrSpecs )? NEWLINE )* ;
    // $ANTLR start "rule_When"
    rule_When: function() {
        var retval = new Nvp1Parser.rule_When_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var a = null;
        var z = null;
        var string_literal22 = null;
        var ARROW23 = null;
        var NEWLINE24 = null;
        var ARROW25 = null;
        var NEWLINE26 = null;
         var aa = null;
         var cond = null;
         var za = null;

        var a_tree=null;
        var z_tree=null;
        var string_literal22_tree=null;
        var ARROW23_tree=null;
        var NEWLINE24_tree=null;
        var ARROW25_tree=null;
        var NEWLINE26_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:50:10: ( '$when' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? ARROW z= ID (za= rule_AttrSpecs )? NEWLINE ( ARROW z= ID (za= rule_AttrSpecs )? NEWLINE )* )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:2: '$when' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? ARROW z= ID (za= rule_AttrSpecs )? NEWLINE ( ARROW z= ID (za= rule_AttrSpecs )? NEWLINE )*
            root_0 = this.adaptor.nil();

            string_literal22=this.match(this.input,16,Nvp1Parser.FOLLOW_16_in_rule_When216); 
            string_literal22_tree = this.adaptor.create(string_literal22);
            this.adaptor.addChild(root_0, string_literal22_tree);

            a=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_When220); 
            a_tree = this.adaptor.create(a);
            this.adaptor.addChild(root_0, a_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:17: (aa= rule_Attrs )?
            var alt7=2;
            var LA7_0 = this.input.LA(1);

            if ( (LA7_0==23) ) {
                alt7=1;
            }
            switch (alt7) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:17: aa= rule_Attrs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attrs_in_rule_When224);
                    aa=this.rule_Attrs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, aa.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:34: (cond= rule_Condition )?
            var alt8=2;
            var LA8_0 = this.input.LA(1);

            if ( (LA8_0==25) ) {
                alt8=1;
            }
            switch (alt8) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:34: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_When229);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            ARROW23=this.match(this.input,ARROW,Nvp1Parser.FOLLOW_ARROW_in_rule_When232); 
            ARROW23_tree = this.adaptor.create(ARROW23);
            this.adaptor.addChild(root_0, ARROW23_tree);

            z=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_When236); 
            z_tree = this.adaptor.create(z);
            this.adaptor.addChild(root_0, z_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:64: (za= rule_AttrSpecs )?
            var alt9=2;
            var LA9_0 = this.input.LA(1);

            if ( (LA9_0==23) ) {
                alt9=1;
            }
            switch (alt9) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:51:64: za= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_When240);
                    za=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, za.getTree());


                    break;

            }

            NEWLINE24=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_When244); 
            NEWLINE24_tree = this.adaptor.create(NEWLINE24);
            this.adaptor.addChild(root_0, NEWLINE24_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:52:2: ( ARROW z= ID (za= rule_AttrSpecs )? NEWLINE )*
            loop11:
            do {
                var alt11=2;
                var LA11_0 = this.input.LA(1);

                if ( (LA11_0==ARROW) ) {
                    alt11=1;
                }


                switch (alt11) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:52:4: ARROW z= ID (za= rule_AttrSpecs )? NEWLINE
                    ARROW25=this.match(this.input,ARROW,Nvp1Parser.FOLLOW_ARROW_in_rule_When249); 
                    ARROW25_tree = this.adaptor.create(ARROW25);
                    this.adaptor.addChild(root_0, ARROW25_tree);

                    z=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_When253); 
                    z_tree = this.adaptor.create(z);
                    this.adaptor.addChild(root_0, z_tree);

                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:52:17: (za= rule_AttrSpecs )?
                    var alt10=2;
                    var LA10_0 = this.input.LA(1);

                    if ( (LA10_0==23) ) {
                        alt10=1;
                    }
                    switch (alt10) {
                        case 1 :
                            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:52:17: za= rule_AttrSpecs
                            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_When257);
                            za=this.rule_AttrSpecs();

                            this.state._fsp--;

                            this.adaptor.addChild(root_0, za.getTree());


                            break;

                    }

                    NEWLINE26=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_When260); 
                    NEWLINE26_tree = this.adaptor.create(NEWLINE26);
                    this.adaptor.addChild(root_0, NEWLINE26_tree);



                    break;

                default :
                    break loop11;
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
    rule_Match_return: (function() {
        Nvp1Parser.rule_Match_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Match_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:58:1: rule_Match : '$match' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? NEWLINE ;
    // $ANTLR start "rule_Match"
    rule_Match: function() {
        var retval = new Nvp1Parser.rule_Match_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var a = null;
        var string_literal27 = null;
        var NEWLINE28 = null;
         var aa = null;
         var cond = null;

        var a_tree=null;
        var string_literal27_tree=null;
        var NEWLINE28_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:58:11: ( '$match' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:59:2: '$match' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? NEWLINE
            root_0 = this.adaptor.nil();

            string_literal27=this.match(this.input,17,Nvp1Parser.FOLLOW_17_in_rule_Match275); 
            string_literal27_tree = this.adaptor.create(string_literal27);
            this.adaptor.addChild(root_0, string_literal27_tree);

            a=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_Match279); 
            a_tree = this.adaptor.create(a);
            this.adaptor.addChild(root_0, a_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:59:18: (aa= rule_Attrs )?
            var alt12=2;
            var LA12_0 = this.input.LA(1);

            if ( (LA12_0==23) ) {
                alt12=1;
            }
            switch (alt12) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:59:18: aa= rule_Attrs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attrs_in_rule_Match283);
                    aa=this.rule_Attrs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, aa.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:59:35: (cond= rule_Condition )?
            var alt13=2;
            var LA13_0 = this.input.LA(1);

            if ( (LA13_0==25) ) {
                alt13=1;
            }
            switch (alt13) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:59:35: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_Match288);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            NEWLINE28=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Match292); 
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
    rule_Mock_return: (function() {
        Nvp1Parser.rule_Mock_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Mock_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:1: rule_Mock : '$mock' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' (za= rule_AttrSpecs )? NEWLINE ( '=>' (za= rule_AttrSpecs )? NEWLINE )* ;
    // $ANTLR start "rule_Mock"
    rule_Mock: function() {
        var retval = new Nvp1Parser.rule_Mock_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var a = null;
        var string_literal29 = null;
        var string_literal30 = null;
        var NEWLINE31 = null;
        var string_literal32 = null;
        var NEWLINE33 = null;
         var aa = null;
         var cond = null;
         var za = null;

        var a_tree=null;
        var string_literal29_tree=null;
        var string_literal30_tree=null;
        var NEWLINE31_tree=null;
        var string_literal32_tree=null;
        var NEWLINE33_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:65:10: ( '$mock' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' (za= rule_AttrSpecs )? NEWLINE ( '=>' (za= rule_AttrSpecs )? NEWLINE )* )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:66:2: '$mock' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' (za= rule_AttrSpecs )? NEWLINE ( '=>' (za= rule_AttrSpecs )? NEWLINE )*
            root_0 = this.adaptor.nil();

            string_literal29=this.match(this.input,18,Nvp1Parser.FOLLOW_18_in_rule_Mock304); 
            string_literal29_tree = this.adaptor.create(string_literal29);
            this.adaptor.addChild(root_0, string_literal29_tree);

            a=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_Mock308); 
            a_tree = this.adaptor.create(a);
            this.adaptor.addChild(root_0, a_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:66:17: (aa= rule_Attrs )?
            var alt14=2;
            var LA14_0 = this.input.LA(1);

            if ( (LA14_0==23) ) {
                alt14=1;
            }
            switch (alt14) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:66:17: aa= rule_Attrs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attrs_in_rule_Mock312);
                    aa=this.rule_Attrs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, aa.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:66:34: (cond= rule_Condition )?
            var alt15=2;
            var LA15_0 = this.input.LA(1);

            if ( (LA15_0==25) ) {
                alt15=1;
            }
            switch (alt15) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:66:34: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_Mock317);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            string_literal30=this.match(this.input,19,Nvp1Parser.FOLLOW_19_in_rule_Mock320); 
            string_literal30_tree = this.adaptor.create(string_literal30);
            this.adaptor.addChild(root_0, string_literal30_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:66:58: (za= rule_AttrSpecs )?
            var alt16=2;
            var LA16_0 = this.input.LA(1);

            if ( (LA16_0==23) ) {
                alt16=1;
            }
            switch (alt16) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:66:58: za= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Mock324);
                    za=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, za.getTree());


                    break;

            }

            NEWLINE31=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Mock327); 
            NEWLINE31_tree = this.adaptor.create(NEWLINE31);
            this.adaptor.addChild(root_0, NEWLINE31_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:67:2: ( '=>' (za= rule_AttrSpecs )? NEWLINE )*
            loop18:
            do {
                var alt18=2;
                var LA18_0 = this.input.LA(1);

                if ( (LA18_0==19) ) {
                    alt18=1;
                }


                switch (alt18) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:67:4: '=>' (za= rule_AttrSpecs )? NEWLINE
                    string_literal32=this.match(this.input,19,Nvp1Parser.FOLLOW_19_in_rule_Mock332); 
                    string_literal32_tree = this.adaptor.create(string_literal32);
                    this.adaptor.addChild(root_0, string_literal32_tree);

                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:67:11: (za= rule_AttrSpecs )?
                    var alt17=2;
                    var LA17_0 = this.input.LA(1);

                    if ( (LA17_0==23) ) {
                        alt17=1;
                    }
                    switch (alt17) {
                        case 1 :
                            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:67:11: za= rule_AttrSpecs
                            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Mock336);
                            za=this.rule_AttrSpecs();

                            this.state._fsp--;

                            this.adaptor.addChild(root_0, za.getTree());


                            break;

                    }

                    NEWLINE33=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Mock339); 
                    NEWLINE33_tree = this.adaptor.create(NEWLINE33);
                    this.adaptor.addChild(root_0, NEWLINE33_tree);



                    break;

                default :
                    break loop18;
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
    rule_Flow_return: (function() {
        Nvp1Parser.rule_Flow_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_Flow_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:73:1: rule_Flow : '$flow' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' expr= rule_FlowExprA NEWLINE ;
    // $ANTLR start "rule_Flow"
    rule_Flow: function() {
        var retval = new Nvp1Parser.rule_Flow_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var a = null;
        var string_literal34 = null;
        var string_literal35 = null;
        var NEWLINE36 = null;
         var aa = null;
         var cond = null;
         var expr = null;

        var a_tree=null;
        var string_literal34_tree=null;
        var string_literal35_tree=null;
        var NEWLINE36_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:73:10: ( '$flow' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' expr= rule_FlowExprA NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:74:2: '$flow' a= ID (aa= rule_Attrs )? (cond= rule_Condition )? '=>' expr= rule_FlowExprA NEWLINE
            root_0 = this.adaptor.nil();

            string_literal34=this.match(this.input,20,Nvp1Parser.FOLLOW_20_in_rule_Flow355); 
            string_literal34_tree = this.adaptor.create(string_literal34);
            this.adaptor.addChild(root_0, string_literal34_tree);

            a=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_Flow359); 
            a_tree = this.adaptor.create(a);
            this.adaptor.addChild(root_0, a_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:74:17: (aa= rule_Attrs )?
            var alt19=2;
            var LA19_0 = this.input.LA(1);

            if ( (LA19_0==23) ) {
                alt19=1;
            }
            switch (alt19) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:74:17: aa= rule_Attrs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attrs_in_rule_Flow363);
                    aa=this.rule_Attrs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, aa.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:74:34: (cond= rule_Condition )?
            var alt20=2;
            var LA20_0 = this.input.LA(1);

            if ( (LA20_0==25) ) {
                alt20=1;
            }
            switch (alt20) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:74:34: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_Flow368);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            string_literal35=this.match(this.input,19,Nvp1Parser.FOLLOW_19_in_rule_Flow371); 
            string_literal35_tree = this.adaptor.create(string_literal35);
            this.adaptor.addChild(root_0, string_literal35_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_FlowExprA_in_rule_Flow375);
            expr=this.rule_FlowExprA();

            this.state._fsp--;

            this.adaptor.addChild(root_0, expr.getTree());
            NEWLINE36=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Flow378); 
            NEWLINE36_tree = this.adaptor.create(NEWLINE36);
            this.adaptor.addChild(root_0, NEWLINE36_tree);




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
    rule_FlowExprA_return: (function() {
        Nvp1Parser.rule_FlowExprA_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_FlowExprA_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:80:1: rule_FlowExprA : a= rule_FlowExprP ( '+' b+= rule_FlowExprP )* ;
    // $ANTLR start "rule_FlowExprA"
    rule_FlowExprA: function() {
        var retval = new Nvp1Parser.rule_FlowExprA_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal37 = null;
        var list_b=null;
         var a = null;
        var b = null;
        var char_literal37_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:80:15: (a= rule_FlowExprP ( '+' b+= rule_FlowExprP )* )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:81:3: a= rule_FlowExprP ( '+' b+= rule_FlowExprP )*
            root_0 = this.adaptor.nil();

            this.pushFollow(Nvp1Parser.FOLLOW_rule_FlowExprP_in_rule_FlowExprA393);
            a=this.rule_FlowExprP();

            this.state._fsp--;

            this.adaptor.addChild(root_0, a.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:81:20: ( '+' b+= rule_FlowExprP )*
            loop21:
            do {
                var alt21=2;
                var LA21_0 = this.input.LA(1);

                if ( (LA21_0==21) ) {
                    alt21=1;
                }


                switch (alt21) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:81:22: '+' b+= rule_FlowExprP
                    char_literal37=this.match(this.input,21,Nvp1Parser.FOLLOW_21_in_rule_FlowExprA397); 
                    char_literal37_tree = this.adaptor.create(char_literal37);
                    this.adaptor.addChild(root_0, char_literal37_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_FlowExprP_in_rule_FlowExprA401);
                    b=this.rule_FlowExprP();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, b.getTree());
                    if (org.antlr.lang.isNull(list_b)) list_b = [];
                    list_b.push(b.getTree());



                    break;

                default :
                    break loop21;
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
    rule_FlowExprP_return: (function() {
        Nvp1Parser.rule_FlowExprP_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_FlowExprP_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:87:1: rule_FlowExprP : a= rule_FlowExprT ( '|' b+= rule_FlowExprT )* ;
    // $ANTLR start "rule_FlowExprP"
    rule_FlowExprP: function() {
        var retval = new Nvp1Parser.rule_FlowExprP_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal38 = null;
        var list_b=null;
         var a = null;
        var b = null;
        var char_literal38_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:87:15: (a= rule_FlowExprT ( '|' b+= rule_FlowExprT )* )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:88:3: a= rule_FlowExprT ( '|' b+= rule_FlowExprT )*
            root_0 = this.adaptor.nil();

            this.pushFollow(Nvp1Parser.FOLLOW_rule_FlowExprT_in_rule_FlowExprP418);
            a=this.rule_FlowExprT();

            this.state._fsp--;

            this.adaptor.addChild(root_0, a.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:88:20: ( '|' b+= rule_FlowExprT )*
            loop22:
            do {
                var alt22=2;
                var LA22_0 = this.input.LA(1);

                if ( (LA22_0==22) ) {
                    alt22=1;
                }


                switch (alt22) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:88:21: '|' b+= rule_FlowExprT
                    char_literal38=this.match(this.input,22,Nvp1Parser.FOLLOW_22_in_rule_FlowExprP421); 
                    char_literal38_tree = this.adaptor.create(char_literal38);
                    this.adaptor.addChild(root_0, char_literal38_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_FlowExprT_in_rule_FlowExprP425);
                    b=this.rule_FlowExprT();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, b.getTree());
                    if (org.antlr.lang.isNull(list_b)) list_b = [];
                    list_b.push(b.getTree());



                    break;

                default :
                    break loop22;
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
    rule_FlowExprT_return: (function() {
        Nvp1Parser.rule_FlowExprT_return = function(){};
        org.antlr.lang.extend(Nvp1Parser.rule_FlowExprT_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:94:1: rule_FlowExprT : (m= ID | '(' rule_FlowExprA ')' );
    // $ANTLR start "rule_FlowExprT"
    rule_FlowExprT: function() {
        var retval = new Nvp1Parser.rule_FlowExprT_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var m = null;
        var char_literal39 = null;
        var char_literal41 = null;
         var rule_FlowExprA40 = null;

        var m_tree=null;
        var char_literal39_tree=null;
        var char_literal41_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:94:15: (m= ID | '(' rule_FlowExprA ')' )
            var alt23=2;
            var LA23_0 = this.input.LA(1);

            if ( (LA23_0==ID) ) {
                alt23=1;
            }
            else if ( (LA23_0==23) ) {
                alt23=2;
            }
            else {
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 23, 0, this.input);

                throw nvae;
            }
            switch (alt23) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:95:3: m= ID
                    root_0 = this.adaptor.nil();

                    m=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_FlowExprT442); 
                    m_tree = this.adaptor.create(m);
                    this.adaptor.addChild(root_0, m_tree);



                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:95:10: '(' rule_FlowExprA ')'
                    root_0 = this.adaptor.nil();

                    char_literal39=this.match(this.input,23,Nvp1Parser.FOLLOW_23_in_rule_FlowExprT446); 
                    char_literal39_tree = this.adaptor.create(char_literal39);
                    this.adaptor.addChild(root_0, char_literal39_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_FlowExprA_in_rule_FlowExprT448);
                    rule_FlowExprA40=this.rule_FlowExprA();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_FlowExprA40.getTree());
                    char_literal41=this.match(this.input,24,Nvp1Parser.FOLLOW_24_in_rule_FlowExprT450); 
                    char_literal41_tree = this.adaptor.create(char_literal41);
                    this.adaptor.addChild(root_0, char_literal41_tree);



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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:101:1: rule_Expect : ( rule_ExpectM | rule_ExpectV );
    // $ANTLR start "rule_Expect"
    rule_Expect: function() {
        var retval = new Nvp1Parser.rule_Expect_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

         var rule_ExpectM42 = null;
         var rule_ExpectV43 = null;


        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:101:12: ( rule_ExpectM | rule_ExpectV )
            var alt24=2;
            var LA24_0 = this.input.LA(1);

            if ( (LA24_0==26) ) {
                var LA24_1 = this.input.LA(2);

                if ( (LA24_1==15) ) {
                    alt24=1;
                }
                else if ( (LA24_1==27) ) {
                    alt24=2;
                }
                else {
                    var nvae =
                        new org.antlr.runtime.NoViableAltException("", 24, 1, this.input);

                    throw nvae;
                }
            }
            else {
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 24, 0, this.input);

                throw nvae;
            }
            switch (alt24) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:102:5: rule_ExpectM
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_ExpectM_in_rule_Expect465);
                    rule_ExpectM42=this.rule_ExpectM();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_ExpectM42.getTree());


                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:102:20: rule_ExpectV
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_ExpectV_in_rule_Expect469);
                    rule_ExpectV43=this.rule_ExpectV();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, rule_ExpectV43.getTree());


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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:108:1: rule_Condition : '$if' attrs= rule_AttrSpecs ;
    // $ANTLR start "rule_Condition"
    rule_Condition: function() {
        var retval = new Nvp1Parser.rule_Condition_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal44 = null;
         var attrs = null;

        var string_literal44_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:108:15: ( '$if' attrs= rule_AttrSpecs )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:109:5: '$if' attrs= rule_AttrSpecs
            root_0 = this.adaptor.nil();

            string_literal44=this.match(this.input,25,Nvp1Parser.FOLLOW_25_in_rule_Condition484); 
            string_literal44_tree = this.adaptor.create(string_literal44);
            this.adaptor.addChild(root_0, string_literal44_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_Condition488);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:115:1: rule_ExpectM : '$expect' ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? ) (cond= rule_Condition )? NEWLINE ;
    // $ANTLR start "rule_ExpectM"
    rule_ExpectM: function() {
        var retval = new Nvp1Parser.rule_ExpectM_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal45 = null;
        var string_literal46 = null;
        var NEWLINE47 = null;
         var name = null;
         var attrs = null;
         var cond = null;

        var string_literal45_tree=null;
        var string_literal46_tree=null;
        var NEWLINE47_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:115:13: ( '$expect' ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? ) (cond= rule_Condition )? NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:5: '$expect' ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? ) (cond= rule_Condition )? NEWLINE
            root_0 = this.adaptor.nil();

            string_literal45=this.match(this.input,26,Nvp1Parser.FOLLOW_26_in_rule_ExpectM503); 
            string_literal45_tree = this.adaptor.create(string_literal45);
            this.adaptor.addChild(root_0, string_literal45_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:15: ( '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:16: '$msg' name= rule_QualifiedName (attrs= rule_AttrSpecs )?
            string_literal46=this.match(this.input,15,Nvp1Parser.FOLLOW_15_in_rule_ExpectM506); 
            string_literal46_tree = this.adaptor.create(string_literal46);
            this.adaptor.addChild(root_0, string_literal46_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_ExpectM510);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:52: (attrs= rule_AttrSpecs )?
            var alt25=2;
            var LA25_0 = this.input.LA(1);

            if ( (LA25_0==23) ) {
                alt25=1;
            }
            switch (alt25) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:52: attrs= rule_AttrSpecs
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpecs_in_rule_ExpectM514);
                    attrs=this.rule_AttrSpecs();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());


                    break;

            }




            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:70: (cond= rule_Condition )?
            var alt26=2;
            var LA26_0 = this.input.LA(1);

            if ( (LA26_0==25) ) {
                alt26=1;
            }
            switch (alt26) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:116:71: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_ExpectM521);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            NEWLINE47=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_ExpectM524); 
            NEWLINE47_tree = this.adaptor.create(NEWLINE47);
            this.adaptor.addChild(root_0, NEWLINE47_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:122:1: rule_ExpectV : '$expect' ( '$val' (p= rule_AttrSpec )? ) (cond= rule_Condition )? NEWLINE ;
    // $ANTLR start "rule_ExpectV"
    rule_ExpectV: function() {
        var retval = new Nvp1Parser.rule_ExpectV_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal48 = null;
        var string_literal49 = null;
        var NEWLINE50 = null;
         var p = null;
         var cond = null;

        var string_literal48_tree=null;
        var string_literal49_tree=null;
        var NEWLINE50_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:122:13: ( '$expect' ( '$val' (p= rule_AttrSpec )? ) (cond= rule_Condition )? NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:5: '$expect' ( '$val' (p= rule_AttrSpec )? ) (cond= rule_Condition )? NEWLINE
            root_0 = this.adaptor.nil();

            string_literal48=this.match(this.input,26,Nvp1Parser.FOLLOW_26_in_rule_ExpectV539); 
            string_literal48_tree = this.adaptor.create(string_literal48);
            this.adaptor.addChild(root_0, string_literal48_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:15: ( '$val' (p= rule_AttrSpec )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:16: '$val' (p= rule_AttrSpec )?
            string_literal49=this.match(this.input,27,Nvp1Parser.FOLLOW_27_in_rule_ExpectV542); 
            string_literal49_tree = this.adaptor.create(string_literal49);
            this.adaptor.addChild(root_0, string_literal49_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:24: (p= rule_AttrSpec )?
            var alt27=2;
            var LA27_0 = this.input.LA(1);

            if ( (LA27_0==ID) ) {
                alt27=1;
            }
            switch (alt27) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:24: p= rule_AttrSpec
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_ExpectV546);
                    p=this.rule_AttrSpec();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, p.getTree());


                    break;

            }




            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:41: (cond= rule_Condition )?
            var alt28=2;
            var LA28_0 = this.input.LA(1);

            if ( (LA28_0==25) ) {
                alt28=1;
            }
            switch (alt28) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:123:42: cond= rule_Condition
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Condition_in_rule_ExpectV553);
                    cond=this.rule_Condition();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, cond.getTree());


                    break;

            }

            NEWLINE50=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_ExpectV556); 
            NEWLINE50_tree = this.adaptor.create(NEWLINE50);
            this.adaptor.addChild(root_0, NEWLINE50_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:129:1: rule_Val : '$val' p= rule_AttrSpec NEWLINE ;
    // $ANTLR start "rule_Val"
    rule_Val: function() {
        var retval = new Nvp1Parser.rule_Val_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal51 = null;
        var NEWLINE52 = null;
         var p = null;

        var string_literal51_tree=null;
        var NEWLINE52_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:129:9: ( '$val' p= rule_AttrSpec NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:130:5: '$val' p= rule_AttrSpec NEWLINE
            root_0 = this.adaptor.nil();

            string_literal51=this.match(this.input,27,Nvp1Parser.FOLLOW_27_in_rule_Val571); 
            string_literal51_tree = this.adaptor.create(string_literal51);
            this.adaptor.addChild(root_0, string_literal51_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_Val575);
            p=this.rule_AttrSpec();

            this.state._fsp--;

            this.adaptor.addChild(root_0, p.getTree());
            NEWLINE52=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Val577); 
            NEWLINE52_tree = this.adaptor.create(NEWLINE52);
            this.adaptor.addChild(root_0, NEWLINE52_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:136:1: rule_Option : '$opt' attr= rule_AttrSpec NEWLINE ;
    // $ANTLR start "rule_Option"
    rule_Option: function() {
        var retval = new Nvp1Parser.rule_Option_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal53 = null;
        var NEWLINE54 = null;
         var attr = null;

        var string_literal53_tree=null;
        var NEWLINE54_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:136:12: ( '$opt' attr= rule_AttrSpec NEWLINE )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:137:5: '$opt' attr= rule_AttrSpec NEWLINE
            root_0 = this.adaptor.nil();

            string_literal53=this.match(this.input,28,Nvp1Parser.FOLLOW_28_in_rule_Option592); 
            string_literal53_tree = this.adaptor.create(string_literal53);
            this.adaptor.addChild(root_0, string_literal53_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_Option596);
            attr=this.rule_AttrSpec();

            this.state._fsp--;

            this.adaptor.addChild(root_0, attr.getTree());
            NEWLINE54=this.match(this.input,NEWLINE,Nvp1Parser.FOLLOW_NEWLINE_in_rule_Option598); 
            NEWLINE54_tree = this.adaptor.create(NEWLINE54);
            this.adaptor.addChild(root_0, NEWLINE54_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:143:1: rule_AttrSpec : name= rule_QualifiedName ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? ;
    // $ANTLR start "rule_AttrSpec"
    rule_AttrSpec: function() {
        var retval = new Nvp1Parser.rule_AttrSpec_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal55 = null;
        var char_literal56 = null;
         var name = null;
         var ttype = null;
         var eexpr = null;

        var char_literal55_tree=null;
        var char_literal56_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:143:14: (name= rule_QualifiedName ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:144:3: name= rule_QualifiedName ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )?
            root_0 = this.adaptor.nil();

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_AttrSpec613);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:144:27: ( ':' ttype= rule_DataType )?
            var alt29=2;
            var LA29_0 = this.input.LA(1);

            if ( (LA29_0==29) ) {
                alt29=1;
            }
            switch (alt29) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:144:28: ':' ttype= rule_DataType
                    char_literal55=this.match(this.input,29,Nvp1Parser.FOLLOW_29_in_rule_AttrSpec616); 
                    char_literal55_tree = this.adaptor.create(char_literal55);
                    this.adaptor.addChild(root_0, char_literal55_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_DataType_in_rule_AttrSpec620);
                    ttype=this.rule_DataType();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, ttype.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:144:54: ( '=' eexpr= rule_EXPR )?
            var alt30=2;
            var LA30_0 = this.input.LA(1);

            if ( (LA30_0==30) ) {
                alt30=1;
            }
            switch (alt30) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:144:55: '=' eexpr= rule_EXPR
                    char_literal56=this.match(this.input,30,Nvp1Parser.FOLLOW_30_in_rule_AttrSpec625); 
                    char_literal56_tree = this.adaptor.create(char_literal56);
                    this.adaptor.addChild(root_0, char_literal56_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_EXPR_in_rule_AttrSpec629);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:149:1: rule_Attr : name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? ;
    // $ANTLR start "rule_Attr"
    rule_Attr: function() {
        var retval = new Nvp1Parser.rule_Attr_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var name = null;
        var char_literal57 = null;
        var char_literal58 = null;
         var ttype = null;
         var eexpr = null;

        var name_tree=null;
        var char_literal57_tree=null;
        var char_literal58_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:149:10: (name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:150:3: name= ID ( ':' ttype= rule_DataType )? ( '=' eexpr= rule_EXPR )?
            root_0 = this.adaptor.nil();

            name=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_Attr645); 
            name_tree = this.adaptor.create(name);
            this.adaptor.addChild(root_0, name_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:150:11: ( ':' ttype= rule_DataType )?
            var alt31=2;
            var LA31_0 = this.input.LA(1);

            if ( (LA31_0==29) ) {
                alt31=1;
            }
            switch (alt31) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:150:12: ':' ttype= rule_DataType
                    char_literal57=this.match(this.input,29,Nvp1Parser.FOLLOW_29_in_rule_Attr648); 
                    char_literal57_tree = this.adaptor.create(char_literal57);
                    this.adaptor.addChild(root_0, char_literal57_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_DataType_in_rule_Attr652);
                    ttype=this.rule_DataType();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, ttype.getTree());


                    break;

            }

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:150:38: ( '=' eexpr= rule_EXPR )?
            var alt32=2;
            var LA32_0 = this.input.LA(1);

            if ( (LA32_0==30) ) {
                alt32=1;
            }
            switch (alt32) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:150:39: '=' eexpr= rule_EXPR
                    char_literal58=this.match(this.input,30,Nvp1Parser.FOLLOW_30_in_rule_Attr657); 
                    char_literal58_tree = this.adaptor.create(char_literal58);
                    this.adaptor.addChild(root_0, char_literal58_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_EXPR_in_rule_Attr661);
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:155:1: rule_EXPR : (parm= rule_QualifiedName | svalue= STRING | ivalue= INT );
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
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:155:10: (parm= rule_QualifiedName | svalue= STRING | ivalue= INT )
            var alt33=3;
            switch ( this.input.LA(1) ) {
            case ID:
                alt33=1;
                break;
            case STRING:
                alt33=2;
                break;
            case INT:
                alt33=3;
                break;
            default:
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 33, 0, this.input);

                throw nvae;
            }

            switch (alt33) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:156:3: parm= rule_QualifiedName
                    root_0 = this.adaptor.nil();

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_EXPR677);
                    parm=this.rule_QualifiedName();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, parm.getTree());


                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:156:29: svalue= STRING
                    root_0 = this.adaptor.nil();

                    svalue=this.match(this.input,STRING,Nvp1Parser.FOLLOW_STRING_in_rule_EXPR683); 
                    svalue_tree = this.adaptor.create(svalue);
                    this.adaptor.addChild(root_0, svalue_tree);



                    break;
                case 3 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:156:45: ivalue= INT
                    root_0 = this.adaptor.nil();

                    ivalue=this.match(this.input,INT,Nvp1Parser.FOLLOW_INT_in_rule_EXPR689); 
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:162:1: rule_Attrs : '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')' ;
    // $ANTLR start "rule_Attrs"
    rule_Attrs: function() {
        var retval = new Nvp1Parser.rule_Attrs_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal59 = null;
        var char_literal60 = null;
        var char_literal61 = null;
        var list_attrs=null;
        var attrs = null;
        var char_literal59_tree=null;
        var char_literal60_tree=null;
        var char_literal61_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:162:11: ( '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:163:5: '(' (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )? ')'
            root_0 = this.adaptor.nil();

            char_literal59=this.match(this.input,23,Nvp1Parser.FOLLOW_23_in_rule_Attrs706); 
            char_literal59_tree = this.adaptor.create(char_literal59);
            this.adaptor.addChild(root_0, char_literal59_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:163:9: (attrs+= rule_Attr ( ',' attrs+= rule_Attr )* )?
            var alt35=2;
            var LA35_0 = this.input.LA(1);

            if ( (LA35_0==ID) ) {
                alt35=1;
            }
            switch (alt35) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:163:10: attrs+= rule_Attr ( ',' attrs+= rule_Attr )*
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_Attr_in_rule_Attrs711);
                    attrs=this.rule_Attr();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());
                    if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                    list_attrs.push(attrs.getTree());

                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:163:27: ( ',' attrs+= rule_Attr )*
                    loop34:
                    do {
                        var alt34=2;
                        var LA34_0 = this.input.LA(1);

                        if ( (LA34_0==31) ) {
                            alt34=1;
                        }


                        switch (alt34) {
                        case 1 :
                            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:163:28: ',' attrs+= rule_Attr
                            char_literal60=this.match(this.input,31,Nvp1Parser.FOLLOW_31_in_rule_Attrs714); 
                            char_literal60_tree = this.adaptor.create(char_literal60);
                            this.adaptor.addChild(root_0, char_literal60_tree);

                            this.pushFollow(Nvp1Parser.FOLLOW_rule_Attr_in_rule_Attrs718);
                            attrs=this.rule_Attr();

                            this.state._fsp--;

                            this.adaptor.addChild(root_0, attrs.getTree());
                            if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                            list_attrs.push(attrs.getTree());



                            break;

                        default :
                            break loop34;
                        }
                    } while (true);



                    break;

            }

            char_literal61=this.match(this.input,24,Nvp1Parser.FOLLOW_24_in_rule_Attrs724); 
            char_literal61_tree = this.adaptor.create(char_literal61);
            this.adaptor.addChild(root_0, char_literal61_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:169:1: rule_AttrSpecs : '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')' ;
    // $ANTLR start "rule_AttrSpecs"
    rule_AttrSpecs: function() {
        var retval = new Nvp1Parser.rule_AttrSpecs_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal62 = null;
        var char_literal63 = null;
        var char_literal64 = null;
        var list_attrs=null;
        var attrs = null;
        var char_literal62_tree=null;
        var char_literal63_tree=null;
        var char_literal64_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:169:15: ( '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:170:4: '(' (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )? ')'
            root_0 = this.adaptor.nil();

            char_literal62=this.match(this.input,23,Nvp1Parser.FOLLOW_23_in_rule_AttrSpecs738); 
            char_literal62_tree = this.adaptor.create(char_literal62);
            this.adaptor.addChild(root_0, char_literal62_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:170:8: (attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )* )?
            var alt37=2;
            var LA37_0 = this.input.LA(1);

            if ( (LA37_0==ID) ) {
                alt37=1;
            }
            switch (alt37) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:170:9: attrs+= rule_AttrSpec ( ',' attrs+= rule_AttrSpec )*
                    this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_AttrSpecs743);
                    attrs=this.rule_AttrSpec();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, attrs.getTree());
                    if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                    list_attrs.push(attrs.getTree());

                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:170:30: ( ',' attrs+= rule_AttrSpec )*
                    loop36:
                    do {
                        var alt36=2;
                        var LA36_0 = this.input.LA(1);

                        if ( (LA36_0==31) ) {
                            alt36=1;
                        }


                        switch (alt36) {
                        case 1 :
                            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:170:31: ',' attrs+= rule_AttrSpec
                            char_literal63=this.match(this.input,31,Nvp1Parser.FOLLOW_31_in_rule_AttrSpecs746); 
                            char_literal63_tree = this.adaptor.create(char_literal63);
                            this.adaptor.addChild(root_0, char_literal63_tree);

                            this.pushFollow(Nvp1Parser.FOLLOW_rule_AttrSpec_in_rule_AttrSpecs750);
                            attrs=this.rule_AttrSpec();

                            this.state._fsp--;

                            this.adaptor.addChild(root_0, attrs.getTree());
                            if (org.antlr.lang.isNull(list_attrs)) list_attrs = [];
                            list_attrs.push(attrs.getTree());



                            break;

                        default :
                            break loop36;
                        }
                    } while (true);



                    break;

            }

            char_literal64=this.match(this.input,24,Nvp1Parser.FOLLOW_24_in_rule_AttrSpecs756); 
            char_literal64_tree = this.adaptor.create(char_literal64);
            this.adaptor.addChild(root_0, char_literal64_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:176:1: rule_Topic : '[[' name= rule_QualifiedName ( ':' t= rule_QualifiedName )? ']]' ;
    // $ANTLR start "rule_Topic"
    rule_Topic: function() {
        var retval = new Nvp1Parser.rule_Topic_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal65 = null;
        var char_literal66 = null;
        var string_literal67 = null;
         var name = null;
         var t = null;

        var string_literal65_tree=null;
        var char_literal66_tree=null;
        var string_literal67_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:176:11: ( '[[' name= rule_QualifiedName ( ':' t= rule_QualifiedName )? ']]' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:177:5: '[[' name= rule_QualifiedName ( ':' t= rule_QualifiedName )? ']]'
            root_0 = this.adaptor.nil();

            string_literal65=this.match(this.input,32,Nvp1Parser.FOLLOW_32_in_rule_Topic771); 
            string_literal65_tree = this.adaptor.create(string_literal65);
            this.adaptor.addChild(root_0, string_literal65_tree);

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_Topic775);
            name=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, name.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:177:34: ( ':' t= rule_QualifiedName )?
            var alt38=2;
            var LA38_0 = this.input.LA(1);

            if ( (LA38_0==29) ) {
                alt38=1;
            }
            switch (alt38) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:177:35: ':' t= rule_QualifiedName
                    char_literal66=this.match(this.input,29,Nvp1Parser.FOLLOW_29_in_rule_Topic778); 
                    char_literal66_tree = this.adaptor.create(char_literal66);
                    this.adaptor.addChild(root_0, char_literal66_tree);

                    this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_Topic782);
                    t=this.rule_QualifiedName();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, t.getTree());


                    break;

            }

            string_literal67=this.match(this.input,33,Nvp1Parser.FOLLOW_33_in_rule_Topic786); 
            string_literal67_tree = this.adaptor.create(string_literal67);
            this.adaptor.addChild(root_0, string_literal67_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:183:1: rule_Braq : '}' ;
    // $ANTLR start "rule_Braq"
    rule_Braq: function() {
        var retval = new Nvp1Parser.rule_Braq_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var char_literal68 = null;

        var char_literal68_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:183:10: ( '}' )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:184:5: '}'
            root_0 = this.adaptor.nil();

            char_literal68=this.match(this.input,34,Nvp1Parser.FOLLOW_34_in_rule_Braq801); 
            char_literal68_tree = this.adaptor.create(char_literal68);
            this.adaptor.addChild(root_0, char_literal68_tree);




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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:193:1: rule_QualifiedNameWithWildCard : rule_QualifiedName ( '.*' )? ;
    // $ANTLR start "rule_QualifiedNameWithWildCard"
    rule_QualifiedNameWithWildCard: function() {
        var retval = new Nvp1Parser.rule_QualifiedNameWithWildCard_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal70 = null;
         var rule_QualifiedName69 = null;

        var string_literal70_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:193:31: ( rule_QualifiedName ( '.*' )? )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:194:5: rule_QualifiedName ( '.*' )?
            root_0 = this.adaptor.nil();

            this.pushFollow(Nvp1Parser.FOLLOW_rule_QualifiedName_in_rule_QualifiedNameWithWildCard819);
            rule_QualifiedName69=this.rule_QualifiedName();

            this.state._fsp--;

            this.adaptor.addChild(root_0, rule_QualifiedName69.getTree());
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:194:24: ( '.*' )?
            var alt39=2;
            var LA39_0 = this.input.LA(1);

            if ( (LA39_0==35) ) {
                alt39=1;
            }
            switch (alt39) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:194:24: '.*'
                    string_literal70=this.match(this.input,35,Nvp1Parser.FOLLOW_35_in_rule_QualifiedNameWithWildCard821); 
                    string_literal70_tree = this.adaptor.create(string_literal70);
                    this.adaptor.addChild(root_0, string_literal70_tree);



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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:199:1: rule_QualifiedName : ID ( '.' ID )* ;
    // $ANTLR start "rule_QualifiedName"
    rule_QualifiedName: function() {
        var retval = new Nvp1Parser.rule_QualifiedName_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var ID71 = null;
        var char_literal72 = null;
        var ID73 = null;

        var ID71_tree=null;
        var char_literal72_tree=null;
        var ID73_tree=null;

        try {
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:199:19: ( ID ( '.' ID )* )
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:200:5: ID ( '.' ID )*
            root_0 = this.adaptor.nil();

            ID71=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_QualifiedName836); 
            ID71_tree = this.adaptor.create(ID71);
            this.adaptor.addChild(root_0, ID71_tree);

            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:200:8: ( '.' ID )*
            loop40:
            do {
                var alt40=2;
                var LA40_0 = this.input.LA(1);

                if ( (LA40_0==36) ) {
                    alt40=1;
                }


                switch (alt40) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:200:9: '.' ID
                    char_literal72=this.match(this.input,36,Nvp1Parser.FOLLOW_36_in_rule_QualifiedName839); 
                    char_literal72_tree = this.adaptor.create(char_literal72);
                    this.adaptor.addChild(root_0, char_literal72_tree);

                    ID73=this.match(this.input,ID,Nvp1Parser.FOLLOW_ID_in_rule_QualifiedName841); 
                    ID73_tree = this.adaptor.create(ID73);
                    this.adaptor.addChild(root_0, ID73_tree);



                    break;

                default :
                    break loop40;
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:203:1: rule_DataType : (string= 'String' | int= 'Int' | date= 'Date' );
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
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:203:14: (string= 'String' | int= 'Int' | date= 'Date' )
            var alt41=3;
            switch ( this.input.LA(1) ) {
            case 37:
                alt41=1;
                break;
            case 38:
                alt41=2;
                break;
            case 39:
                alt41=3;
                break;
            default:
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 41, 0, this.input);

                throw nvae;
            }

            switch (alt41) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:204:2: string= 'String'
                    root_0 = this.adaptor.nil();

                    string=this.match(this.input,37,Nvp1Parser.FOLLOW_37_in_rule_DataType854); 
                    string_tree = this.adaptor.create(string);
                    this.adaptor.addChild(root_0, string_tree);



                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:204:20: int= 'Int'
                    root_0 = this.adaptor.nil();

                    int=this.match(this.input,38,Nvp1Parser.FOLLOW_38_in_rule_DataType860); 
                    int_tree = this.adaptor.create(int);
                    this.adaptor.addChild(root_0, int_tree);



                    break;
                case 3 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:204:32: date= 'Date'
                    root_0 = this.adaptor.nil();

                    date=this.match(this.input,39,Nvp1Parser.FOLLOW_39_in_rule_DataType866); 
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

    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:207:1: rule_MsgStereo : (gET= 'GET' | pOST= 'POST' | camel= 'Camel' | jS= 'JS' | java= 'Java' );
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
            // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:207:15: (gET= 'GET' | pOST= 'POST' | camel= 'Camel' | jS= 'JS' | java= 'Java' )
            var alt42=5;
            switch ( this.input.LA(1) ) {
            case 40:
                alt42=1;
                break;
            case 41:
                alt42=2;
                break;
            case 42:
                alt42=3;
                break;
            case 43:
                alt42=4;
                break;
            case 44:
                alt42=5;
                break;
            default:
                var nvae =
                    new org.antlr.runtime.NoViableAltException("", 42, 0, this.input);

                throw nvae;
            }

            switch (alt42) {
                case 1 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:208:2: gET= 'GET'
                    root_0 = this.adaptor.nil();

                    gET=this.match(this.input,40,Nvp1Parser.FOLLOW_40_in_rule_MsgStereo877); 
                    gET_tree = this.adaptor.create(gET);
                    this.adaptor.addChild(root_0, gET_tree);



                    break;
                case 2 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:208:14: pOST= 'POST'
                    root_0 = this.adaptor.nil();

                    pOST=this.match(this.input,41,Nvp1Parser.FOLLOW_41_in_rule_MsgStereo883); 
                    pOST_tree = this.adaptor.create(pOST);
                    this.adaptor.addChild(root_0, pOST_tree);



                    break;
                case 3 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:208:28: camel= 'Camel'
                    root_0 = this.adaptor.nil();

                    camel=this.match(this.input,42,Nvp1Parser.FOLLOW_42_in_rule_MsgStereo889); 
                    camel_tree = this.adaptor.create(camel);
                    this.adaptor.addChild(root_0, camel_tree);



                    break;
                case 4 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:208:44: jS= 'JS'
                    root_0 = this.adaptor.nil();

                    jS=this.match(this.input,43,Nvp1Parser.FOLLOW_43_in_rule_MsgStereo895); 
                    jS_tree = this.adaptor.create(jS);
                    this.adaptor.addChild(root_0, jS_tree);



                    break;
                case 5 :
                    // /Users/raz/w2/com.razie.dsl1.web/src-js/com/razie/dsl1/web/parser/Nvp1.g:208:54: java= 'Java'
                    root_0 = this.adaptor.nil();

                    java=this.match(this.input,44,Nvp1Parser.FOLLOW_44_in_rule_MsgStereo901); 
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
    tokenNames: ["<invalid>", "<EOR>", "<DOWN>", "<UP>", "TEXT", "NEWLINE", "ID", "ARROW", "STRING", "INT", "COMMENT", "WS", "'$receive'", "'<'", "'>'", "'$msg'", "'$when'", "'$match'", "'$mock'", "'=>'", "'$flow'", "'+'", "'|'", "'('", "')'", "'$if'", "'$expect'", "'$val'", "'$opt'", "':'", "'='", "','", "'[['", "']]'", "'}'", "'.*'", "'.'", "'String'", "'Int'", "'Date'", "'GET'", "'POST'", "'Camel'", "'JS'", "'Java'"],
    FOLLOW_rule_AbstractElement_in_rule_DomainModel67: new org.antlr.runtime.BitSet([0x1C179010, 0x00000005]),
    FOLLOW_EOF_in_rule_DomainModel70: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Expect_in_rule_AbstractElement85: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Msg_in_rule_AbstractElement89: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Match_in_rule_AbstractElement93: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_When_in_rule_AbstractElement97: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Receive_in_rule_AbstractElement101: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Flow_in_rule_AbstractElement105: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Option_in_rule_AbstractElement109: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Val_in_rule_AbstractElement113: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Mock_in_rule_AbstractElement117: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Topic_in_rule_AbstractElement121: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_Braq_in_rule_AbstractElement125: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_TEXT_in_rule_AbstractElement129: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_12_in_rule_Receive145: new org.antlr.runtime.BitSet([0x00002040, 0x00000000]),
    FOLLOW_13_in_rule_Receive148: new org.antlr.runtime.BitSet([0x00000000, 0x00001F00]),
    FOLLOW_rule_MsgStereo_in_rule_Receive152: new org.antlr.runtime.BitSet([0x00004000, 0x00000000]),
    FOLLOW_14_in_rule_Receive154: new org.antlr.runtime.BitSet([0x00002040, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_Receive160: new org.antlr.runtime.BitSet([0x00800020, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Receive164: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Receive167: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_15_in_rule_Msg182: new org.antlr.runtime.BitSet([0x00002040, 0x00000000]),
    FOLLOW_13_in_rule_Msg185: new org.antlr.runtime.BitSet([0x00000000, 0x00001F00]),
    FOLLOW_rule_MsgStereo_in_rule_Msg189: new org.antlr.runtime.BitSet([0x00004000, 0x00000000]),
    FOLLOW_14_in_rule_Msg191: new org.antlr.runtime.BitSet([0x00002040, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_Msg197: new org.antlr.runtime.BitSet([0x00800020, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Msg201: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Msg204: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_16_in_rule_When216: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_ID_in_rule_When220: new org.antlr.runtime.BitSet([0x02800080, 0x00000000]),
    FOLLOW_rule_Attrs_in_rule_When224: new org.antlr.runtime.BitSet([0x02000080, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_When229: new org.antlr.runtime.BitSet([0x00000080, 0x00000000]),
    FOLLOW_ARROW_in_rule_When232: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_ID_in_rule_When236: new org.antlr.runtime.BitSet([0x00800020, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_When240: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_When244: new org.antlr.runtime.BitSet([0x00000082, 0x00000000]),
    FOLLOW_ARROW_in_rule_When249: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_ID_in_rule_When253: new org.antlr.runtime.BitSet([0x00800020, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_When257: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_When260: new org.antlr.runtime.BitSet([0x00000082, 0x00000000]),
    FOLLOW_17_in_rule_Match275: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_ID_in_rule_Match279: new org.antlr.runtime.BitSet([0x02800020, 0x00000000]),
    FOLLOW_rule_Attrs_in_rule_Match283: new org.antlr.runtime.BitSet([0x02000020, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_Match288: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Match292: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_18_in_rule_Mock304: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_ID_in_rule_Mock308: new org.antlr.runtime.BitSet([0x02880000, 0x00000000]),
    FOLLOW_rule_Attrs_in_rule_Mock312: new org.antlr.runtime.BitSet([0x02080000, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_Mock317: new org.antlr.runtime.BitSet([0x00080000, 0x00000000]),
    FOLLOW_19_in_rule_Mock320: new org.antlr.runtime.BitSet([0x00800020, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Mock324: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Mock327: new org.antlr.runtime.BitSet([0x00080002, 0x00000000]),
    FOLLOW_19_in_rule_Mock332: new org.antlr.runtime.BitSet([0x00800020, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Mock336: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Mock339: new org.antlr.runtime.BitSet([0x00080002, 0x00000000]),
    FOLLOW_20_in_rule_Flow355: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_ID_in_rule_Flow359: new org.antlr.runtime.BitSet([0x02880000, 0x00000000]),
    FOLLOW_rule_Attrs_in_rule_Flow363: new org.antlr.runtime.BitSet([0x02080000, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_Flow368: new org.antlr.runtime.BitSet([0x00080000, 0x00000000]),
    FOLLOW_19_in_rule_Flow371: new org.antlr.runtime.BitSet([0x00800040, 0x00000000]),
    FOLLOW_rule_FlowExprA_in_rule_Flow375: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Flow378: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_FlowExprP_in_rule_FlowExprA393: new org.antlr.runtime.BitSet([0x00200002, 0x00000000]),
    FOLLOW_21_in_rule_FlowExprA397: new org.antlr.runtime.BitSet([0x00800040, 0x00000000]),
    FOLLOW_rule_FlowExprP_in_rule_FlowExprA401: new org.antlr.runtime.BitSet([0x00200002, 0x00000000]),
    FOLLOW_rule_FlowExprT_in_rule_FlowExprP418: new org.antlr.runtime.BitSet([0x00400002, 0x00000000]),
    FOLLOW_22_in_rule_FlowExprP421: new org.antlr.runtime.BitSet([0x00800040, 0x00000000]),
    FOLLOW_rule_FlowExprT_in_rule_FlowExprP425: new org.antlr.runtime.BitSet([0x00400002, 0x00000000]),
    FOLLOW_ID_in_rule_FlowExprT442: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_23_in_rule_FlowExprT446: new org.antlr.runtime.BitSet([0x00800040, 0x00000000]),
    FOLLOW_rule_FlowExprA_in_rule_FlowExprT448: new org.antlr.runtime.BitSet([0x01000000, 0x00000000]),
    FOLLOW_24_in_rule_FlowExprT450: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_ExpectM_in_rule_Expect465: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_ExpectV_in_rule_Expect469: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_25_in_rule_Condition484: new org.antlr.runtime.BitSet([0x00800000, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_Condition488: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_26_in_rule_ExpectM503: new org.antlr.runtime.BitSet([0x00008000, 0x00000000]),
    FOLLOW_15_in_rule_ExpectM506: new org.antlr.runtime.BitSet([0x00002040, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_ExpectM510: new org.antlr.runtime.BitSet([0x02800020, 0x00000000]),
    FOLLOW_rule_AttrSpecs_in_rule_ExpectM514: new org.antlr.runtime.BitSet([0x02000020, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_ExpectM521: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_ExpectM524: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_26_in_rule_ExpectV539: new org.antlr.runtime.BitSet([0x08000000, 0x00000000]),
    FOLLOW_27_in_rule_ExpectV542: new org.antlr.runtime.BitSet([0x02002060, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_ExpectV546: new org.antlr.runtime.BitSet([0x02000020, 0x00000000]),
    FOLLOW_rule_Condition_in_rule_ExpectV553: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_ExpectV556: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_27_in_rule_Val571: new org.antlr.runtime.BitSet([0x00002040, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_Val575: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Val577: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_28_in_rule_Option592: new org.antlr.runtime.BitSet([0x00002040, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_Option596: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NEWLINE_in_rule_Option598: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_AttrSpec613: new org.antlr.runtime.BitSet([0x60000002, 0x00000000]),
    FOLLOW_29_in_rule_AttrSpec616: new org.antlr.runtime.BitSet([0x00000000, 0x000000E0]),
    FOLLOW_rule_DataType_in_rule_AttrSpec620: new org.antlr.runtime.BitSet([0x40000002, 0x00000000]),
    FOLLOW_30_in_rule_AttrSpec625: new org.antlr.runtime.BitSet([0x00002340, 0x00000000]),
    FOLLOW_rule_EXPR_in_rule_AttrSpec629: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_ID_in_rule_Attr645: new org.antlr.runtime.BitSet([0x60000002, 0x00000000]),
    FOLLOW_29_in_rule_Attr648: new org.antlr.runtime.BitSet([0x00000000, 0x000000E0]),
    FOLLOW_rule_DataType_in_rule_Attr652: new org.antlr.runtime.BitSet([0x40000002, 0x00000000]),
    FOLLOW_30_in_rule_Attr657: new org.antlr.runtime.BitSet([0x00002340, 0x00000000]),
    FOLLOW_rule_EXPR_in_rule_Attr661: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_EXPR677: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_STRING_in_rule_EXPR683: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_INT_in_rule_EXPR689: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_23_in_rule_Attrs706: new org.antlr.runtime.BitSet([0x01000040, 0x00000000]),
    FOLLOW_rule_Attr_in_rule_Attrs711: new org.antlr.runtime.BitSet([0x81000000, 0x00000000]),
    FOLLOW_31_in_rule_Attrs714: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_rule_Attr_in_rule_Attrs718: new org.antlr.runtime.BitSet([0x81000000, 0x00000000]),
    FOLLOW_24_in_rule_Attrs724: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_23_in_rule_AttrSpecs738: new org.antlr.runtime.BitSet([0x01002040, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_AttrSpecs743: new org.antlr.runtime.BitSet([0x81000000, 0x00000000]),
    FOLLOW_31_in_rule_AttrSpecs746: new org.antlr.runtime.BitSet([0x00002040, 0x00000000]),
    FOLLOW_rule_AttrSpec_in_rule_AttrSpecs750: new org.antlr.runtime.BitSet([0x81000000, 0x00000000]),
    FOLLOW_24_in_rule_AttrSpecs756: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_32_in_rule_Topic771: new org.antlr.runtime.BitSet([0x00002040, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_Topic775: new org.antlr.runtime.BitSet([0x20000000, 0x00000002]),
    FOLLOW_29_in_rule_Topic778: new org.antlr.runtime.BitSet([0x00002040, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_Topic782: new org.antlr.runtime.BitSet([0x00000000, 0x00000002]),
    FOLLOW_33_in_rule_Topic786: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_34_in_rule_Braq801: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_rule_QualifiedName_in_rule_QualifiedNameWithWildCard819: new org.antlr.runtime.BitSet([0x00000002, 0x00000008]),
    FOLLOW_35_in_rule_QualifiedNameWithWildCard821: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_ID_in_rule_QualifiedName836: new org.antlr.runtime.BitSet([0x00000002, 0x00000010]),
    FOLLOW_36_in_rule_QualifiedName839: new org.antlr.runtime.BitSet([0x00000040, 0x00000000]),
    FOLLOW_ID_in_rule_QualifiedName841: new org.antlr.runtime.BitSet([0x00000002, 0x00000010]),
    FOLLOW_37_in_rule_DataType854: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_38_in_rule_DataType860: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_39_in_rule_DataType866: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_40_in_rule_MsgStereo877: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_41_in_rule_MsgStereo883: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_42_in_rule_MsgStereo889: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_43_in_rule_MsgStereo895: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_44_in_rule_MsgStereo901: new org.antlr.runtime.BitSet([0x00000002, 0x00000000])
});

})();