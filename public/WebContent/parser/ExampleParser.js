// $ANTLR 3.3 avr. 19, 2016 01:13:22 /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g 2016-06-03 10:38:23



var ExampleParser = function(input, state) {
    if (!state) {
        state = new org.antlr.runtime.RecognizerSharedState();
    }

    (function(){
    }).call(this);

    ExampleParser.superclass.constructor.call(this, input, state);


         

    /* @todo only create adaptor if output=AST */
    this.adaptor = new org.antlr.runtime.tree.CommonTreeAdaptor();

};

org.antlr.lang.augmentObject(ExampleParser, {
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
// public class variables
var EOF= -1,
    T__11= 11,
    Person= 4,
    NL= 5,
    ID= 6,
    STRING= 7,
    COMMENT= 8,
    WS= 9,
    INT= 10;

// public instance methods/vars
org.antlr.lang.extend(ExampleParser, org.antlr.runtime.Parser, {
        
    setTreeAdaptor: function(adaptor) {
        this.adaptor = adaptor;
    },
    getTreeAdaptor: function() {
        return this.adaptor;
    },

    getTokenNames: function() { return ExampleParser.tokenNames; },
    getGrammarFileName: function() { return "/Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g"; }
});
org.antlr.lang.augmentObject(ExampleParser.prototype, {

    // inline static return class
    rule_Example_return: (function() {
        ExampleParser.rule_Example_return = function(){};
        org.antlr.lang.extend(ExampleParser.rule_Example_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:17:1: rule_Example : ( Person | hello )* EOF ;
    // $ANTLR start "rule_Example"
    rule_Example: function() {
        var retval = new ExampleParser.rule_Example_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var Person1 = null;
        var EOF3 = null;
         var hello2 = null;

        var Person1_tree=null;
        var EOF3_tree=null;

        try {
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:17:14: ( ( Person | hello )* EOF )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:18:2: ( Person | hello )* EOF
            root_0 = this.adaptor.nil();

            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:18:2: ( Person | hello )*
            loop1:
            do {
                var alt1=3;
                var LA1_0 = this.input.LA(1);

                if ( (LA1_0==Person) ) {
                    alt1=1;
                }
                else if ( (LA1_0==11) ) {
                    alt1=2;
                }


                switch (alt1) {
                case 1 :
                    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:18:3: Person
                    Person1=this.match(this.input,Person,ExampleParser.FOLLOW_Person_in_rule_Example59); 
                    Person1_tree = this.adaptor.create(Person1);
                    this.adaptor.addChild(root_0, Person1_tree);



                    break;
                case 2 :
                    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:18:10: hello
                    this.pushFollow(ExampleParser.FOLLOW_hello_in_rule_Example61);
                    hello2=this.hello();

                    this.state._fsp--;

                    this.adaptor.addChild(root_0, hello2.getTree());


                    break;

                default :
                    break loop1;
                }
            } while (true);

            EOF3=this.match(this.input,EOF,ExampleParser.FOLLOW_EOF_in_rule_Example67); 



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
    hello_return: (function() {
        ExampleParser.hello_return = function(){};
        org.antlr.lang.extend(ExampleParser.hello_return,
                          org.antlr.runtime.ParserRuleReturnScope,
        {
            getTree: function() { return this.tree; }
        });
        return;
    })(),

    // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:21:1: hello : 'Hello' someone+= Person NL ;
    // $ANTLR start "hello"
    hello: function() {
        var retval = new ExampleParser.hello_return();
        retval.start = this.input.LT(1);

        var root_0 = null;

        var string_literal4 = null;
        var NL5 = null;
        var someone = null;
        var list_someone=null;

        var string_literal4_tree=null;
        var NL5_tree=null;
        var someone_tree=null;

        try {
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:21:7: ( 'Hello' someone+= Person NL )
            // /Users/raz/w2/org.dslforge.example.web/WebContent/parser/Example.g:22:2: 'Hello' someone+= Person NL
            root_0 = this.adaptor.nil();

            string_literal4=this.match(this.input,11,ExampleParser.FOLLOW_11_in_hello77); 
            string_literal4_tree = this.adaptor.create(string_literal4);
            this.adaptor.addChild(root_0, string_literal4_tree);

            someone=this.match(this.input,Person,ExampleParser.FOLLOW_Person_in_hello81); 
            someone_tree = this.adaptor.create(someone);
            this.adaptor.addChild(root_0, someone_tree);

            if (org.antlr.lang.isNull(list_someone)) list_someone = [];
            list_someone.push(someone);

            NL5=this.match(this.input,NL,ExampleParser.FOLLOW_NL_in_hello83); 
            NL5_tree = this.adaptor.create(NL5);
            this.adaptor.addChild(root_0, NL5_tree);




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
org.antlr.lang.augmentObject(ExampleParser, {
    tokenNames: ["<invalid>", "<EOR>", "<DOWN>", "<UP>", "Person", "NL", "ID", "STRING", "COMMENT", "WS", "INT", "'Hello'"],
    FOLLOW_Person_in_rule_Example59: new org.antlr.runtime.BitSet([0x00000810, 0x00000000]),
    FOLLOW_hello_in_rule_Example61: new org.antlr.runtime.BitSet([0x00000810, 0x00000000]),
    FOLLOW_EOF_in_rule_Example67: new org.antlr.runtime.BitSet([0x00000002, 0x00000000]),
    FOLLOW_11_in_hello77: new org.antlr.runtime.BitSet([0x00000010, 0x00000000]),
    FOLLOW_Person_in_hello81: new org.antlr.runtime.BitSet([0x00000020, 0x00000000]),
    FOLLOW_NL_in_hello83: new org.antlr.runtime.BitSet([0x00000002, 0x00000000])
});

})();