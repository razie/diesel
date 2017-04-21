/**
 * Created by razvanc on 02/05/2014.
 */

/** am I withing brackets? go back and count ) and ( pairs) */
function isInBrackets(line,pos){
  var count=0;
  for(var i=pos-1; i>= 0; i--) {
    if(line.charAt(i) == ')') count = count-1;
    if(line.charAt(i) == '(') count = count+1;
  }
  return count > 0;
}

/** CA content assist for dom DSL */
function domCompl (i) {
  return function (editor, session, pos, prefix, callback) {
    var contentAssist = i ? instContentAssist : domContentAssist;
//      if (prefix.length === 0) { callback(null, wl); return }
    var line = session.getLine(pos.row);
    var terms = line.slice(0, pos.column).split(' ');

    // => entity.action
    if(terms.indexOf('->') == terms.length-1 ||
      terms.indexOf('=>') == terms.length-1 ||
      terms[0] && terms[0] == '$receive' ||
      terms[0] && terms[0] == '$match' ||
      terms[0] && terms[0] == '$msg' ||
      terms[0] && terms[0] == '$when' ||
      terms[0] && terms[0] == '$expect' ||
      terms[0] && terms[0] == '$flow' ||
      terms[0] && terms[0] == '=>' ||
      terms[0] && terms[0] == '$mock') {

      var newTerms = ['msg', ''];
      if(isInBrackets(line,pos.column)) newTerms[0] = 'attr'; // looking for attr

      var reduceDot = '';
      if(
        terms.indexOf('$msg') != terms.length-1 &&
        terms.indexOf('$mock') != terms.length-1 &&
        terms.indexOf('->') != terms.length-1 &&
        terms.indexOf('=>') != terms.length-1) {
        // last is probably a part of it
        newTerms[1] = terms[terms.length-1]
        if(newTerms[1].lastIndexOf('.') > -1)
          reduceDot = newTerms[1].slice(0, newTerms[1].lastIndexOf('.')+1)
      }

      if(newTerms[0] == 'attr') newTerms[1] = newTerms[1].replace(/.*[,()]/, '');

      line = newTerms[0] + ' ';  //msg or attr

      var opts = getOptions(contentAssist, newTerms, 0); // options
      callback(null, opts.map(function(value) {
        // double space is a marker for the FUTURE options
        var xvalue = value.replace(/(\w)  .*/, '\$1');
        var aft = value.replace(/(.*)  /, '');
        var curr = aft.replace(line, '');
        // ace will replace only back to  a dot, so we'll cut it out for them
        // otherwise they'll replace modem.cre with modem.modem.create
        curr = curr.replace(reduceDot, '');
        return {name: value, value: curr, score: 100, meta: "dom"}
      }));
    } else if(terms[terms.length-1].match(/\[\[[^\]]*/)) {
      // CA for wikis
      var top = terms[terms.length-1].match(/\[\[([^\]]*)/)[1];
      console.log('[[]]');
      CA_TC_sqbraTags.search(top, function(opts){
        callback(null, opts.map(function(value) {
          return {name: value, value: value, score: 100, meta: "topic"}
        }));
      });
    } else if(terms[terms.length-1].match(/\{\{[^\]]*(\}\})?/)) {
      // CA for props
      var top = terms[terms.length-1].match(/\{\{([^\]]*)(\}\})?/)[1];
      console.log('{{}}');
      CA_TC_braTags (optsToDomain(braDomain)).search(top, function(opts){
        callback(null, opts.map(function(value) {
          return {name: value, value: value, score: 100, meta: "tags"}
        }));
      });
    } else {
      var opts = getOptions(contentAssist, terms, 0); // options
//      callback(null, opts);
      callback(null, opts.map(function(value) {
        // double space is a marker for the FUTURE options
        var xvalue = value.replace(/(\w)  .*/, '\$1');

//      if (xvalue.indexOf(' ') >= 0)
//        s= ['\{\{' + xvalue + ' ', ''];
//      else if (xvalue.indexOf('/') == 0)
//        s= ['\{\{' + xvalue, '\}\}'];
//      else
//       first time, only one term, insert }}
//        s= ['\{\{' + xvalue + ' ', '\}\}'];

        var curr = xvalue.replace(line, '');
        var aft = value.replace(/(.*)  /, '');
//        return {name: ea.word, value: ea.word, score: ea.score, meta: "rhyme"}
        return {name: value, value: curr, score: 100, meta: "dom"}
      }));
    };
  };
};



function z(m,i) {
  return m && m.length >= i && m[i] ? m[i] : "";
}

var curCap='';

var fh='<form class="form-horizontal">';
var fb='</form>';

function field(name,label,input) {
  return '<div class="form-group">'+
    '<label style="font-weight: bold; font-size: normal;" for="'+name+'" class="col-sm-2 control-label">'+label+'</label>'+
    '<div class="col-sm-10">'+
    '<input class="form-control" id="'+name+'" '+input+'>'+
    '</div>'+
    '</div>';
}

function encParms(s) {
  return s.length > 0 ? '('+s+')' : '';
}

//$when email.update (name,pwd) =>yahoo.upd_pwd (email,password=pwd)
var capWhen = {
  togui : function (s) {
    curCap='when';
    var m=/\$when *([\w*]+)\.([\w*]+) *(\(([^)]*)\))? *=> *([\w*]+)\.([\w*]+) *(\(([^)]*)\))?/.exec(s);
    var e1=z(m,1);
    var a1=z(m,2);
    var p1=z(m,4);
    var e2=z(m,5);
    var a2=z(m,6);
    var p2=z(m,8);

    return '<h3>When</h3>'+
      fh+
      field('entity1', 'Entity', 'value=\''+e1+'\'')+
      field('action1', 'Action', 'value=\''+a1+'\'')+
      field('parms1', 'Parms', 'value=\''+p1+'\'')+
      '<hr>'+
      field('entity2', 'Entity', 'value=\''+e2+'\'')+
      field('action2', 'Action', 'value=\''+a2+'\'')+
      field('parms2', 'Parms', 'value=\''+p2+'\'')+
      fb;
  },

  fromgui : function () {
    return '???';
  }
};

//$msg <GET> billing.get_email (name, url="/bill/email") : (email)
var capMsg = {
  togui : function capMsgTo(s) {
    curCap='msg';
    var m=/\$msg *(<\w+>)? *([\w*]+)\.([\w*]+) *(\(([^)]*)\))?(( *: *)\(([^)]*)\))?/.exec(s);
    var k=z(m,1);
    var e=z(m,2);
    var a=z(m,3);
    var p=z(m,5);
    var r=z(m,8);

    return '<h3>Msg</h3>'+
      fh+
      field('kind',   'Method', 'value=\''+k+'\'')+
      field('entity', 'Entity', 'value=\''+e+'\'')+
      field('action', 'Action', 'value=\''+a+'\'')+
      field('parms',  'Parms', 'value=\''+p+'\'')+
      field('result', 'Result', 'value=\''+r+'\'')+
      fb;
  },

  fromgui : function () {
    var k=$("#kind").val();
    var e=$("#entity").val();
    var a=$("#action").val();
    var p=encParms($("#parms").val());
    var r=$("#result").val();

    return '$msg '+k+' '+e+'.'+a+' '+p +(r.length > 0 ? r : '');
  }
}

//expect $msg <GET> billing.get_email (name, url="/bill/email") : (email)
var capExpectM = {
  togui : function (s) {
    curCap='expect';
    var m=/\$expect \$msg *(<\w+>)? *([\w*]+)\.([\w*]+) *(\([^)]*\))? *(:)? *(\([^)]*\))?/.exec(s);
    var k=z(m,1);
    var e=z(m,2);
    var a=z(m,3);
    var p=z(m,5);

    return '<h3>Expect a message</h3>'+
      fh+
      field('kind',   'Kind', 'value=\''+k+'\'')+
      field('entity', 'Entity', 'value=\''+e+'\'')+
      field('action', 'Action', 'value=\''+a+'\'')+
      field('parms',  'Parms', 'value=\''+p+'\'')+
      fb;
  },

  fromgui : function () {
    return '???';
  }
}

function capTo(s) {
  if(s.match(/\$expect \$msg/)) return capExpectM.togui(s);
  else if(s.match(/\$when/)) return capWhen.togui(s);
  else if(s.match(/\$msg/)) return capMsg.togui(s);
  else return "?";
};

function capFrom() {
  if(curCap == 'msg') return capMsg.fromgui();
  else return "";
};

var saveLine;

var codeGui = function(editor) {
//  var selectionRange = editor.getSelectionRange();
//  var content = editor.session.getTextRange(selectionRange);
  var line = editor.session.getLine(editor.selection.getCursor().row);

  saveLine = function () {
    var s = capFrom();
    if(s.length > 0) {

      var Range = require("ace/range").Range
      var row = editor.selection.getCursor().row
      var newText = s
      editor.session.replace(new Range(row, 0, row, Number.MAX_VALUE), newText)
//    editor.session.getLine(editor.selection.getCursor().row);
    }
    $("#oneModal").modal('hide');
  };

  popupContent(capTo(line) +
    '<hr><button class="btn btn-primary" onclick="saveLine()">Done</button>');
}

var SPEC="SPEC";
var STORY="STORY";
var lastMarker=null;


/** this works IN the fiddle only */
function weSelect(wpath,line,col) {
  var Range = ace.require('ace/range').Range;
  if(lastMarker != null) {
    editor.session.removeMarker(lastMarker);
    editor1.session.removeMarker(lastMarker);
    lastMarker = null;
  }

  if(wpath.includes("Spec:")) {
    editor1.scrollToLine(line, true, true, function () {});
    //editor1.gotoLine(line, 10, true);
    lastMarker = editor1.session.addMarker(new Range(line-1, 0, line-1, 100), "ace-primaryline", "fullLine");
  } else if(wpath.includes("Story:")) {
    editor.scrollToLine(line, true, true, function () {});
    //editor.gotoLine(line, 10, true);
    lastMarker = editor.session.addMarker(new Range(line-1, 0, line-1, 100), "ace-primaryline", "fullLine");
  }
}

/** this works IN the fiddle only */
function weref(wpath,line,col) {
  if(wpath.includes("Spec:")) {
    // is it a different spec?
    if(wpath != specWpath) loadSpec(wpath, function () {
        weSelect(wpath, line, col)
      });
    else weSelect(wpath, line, col);
  } else if(wpath.includes("Story:")) {
    weSelect(wpath, line, col);
  }
}

/** this works from anywhere, to open the fiddle on an element */
function wefiddle(wpath,line,col) {
  if(wpath.includes("Spec:")) {
    window.location.href='/diesel/fiddle/playDom/'+realm+'?line='+line+'&col='+col+'&spec='+wpath
  } else if(wpath.includes("Story:")) {
    window.location.href='/diesel/fiddle/playDom/'+realm+'?line='+line+'&col='+col+'&story='+wpath
  }
}

function loadSpec (wpath, rest) {
  specWpath = wpath;
  var wid = WID(wpath);
  $("#curSpec").text(WID(wpath).name);
  $.ajax( '/diesel/content/Spec/'+wpath, {
    success: function (data) {
      editor1.setValue(data);
      editor1.selection.clearSelection();
      rest();
    },
    error  : rest
  });
}

function WID(wpath) {
  var r = wpath.indexOf(":") > 0 ? wpath.replace(/(\w*)?\..*/, '\$1') : "";
  var c = wpath.indexOf(":") > 0 ? wpath.replace(/(\w*\.)?(\w*):.*/, '\$2') : "";
  var n = wpath.indexOf(":") > 0 ? wpath.replace(/(\w*\.)?(\w*):(\w*)/, '\$3') : wpath;
  return {
    realm : r,
    cat : c,
    name : n,
    contentUrl : '/wikie/content/'+wpath
  }
}

//---------------- configuration

// var domEngineConfig = getEngConfig();

function getEngConfig() {
  $.ajax(
    '/diesel/engine/config/json', {
      type: 'GET',
      success: function(data) {
        domEngineConfig = data;
      },
      error  : function(x) {
        console.log( "ERR can't load engine config: "+JSON.stringify(x));
      }
    });
}

function setEngConfig(cfg) {
  $.ajax(
    '/diesel/engine/config/json', {
      type: 'POST',
      data: $.param({
        domEngineConfig: cfg
      }),
      contentType: 'application/x-www-form-urlencoded',
      success: function (data) {
      },
      error: function (x) {
        console.log("ERR " + x.toString());
      }
    });
}

function setEngConfigElement(name,value) {
  $.ajax(
    '/diesel/engine/config/json', {
      type: 'POST',
      data: $.param({
        domEngineConfig: domEngineConfig
      }),
      contentType: 'application/x-www-form-urlencoded',
      success: function (data) {
      },
      error: function (x) {
        console.log("ERR " + x.toString());
      }
    });
}

/** update the tag query from checkboxes */
function cbToTagQuery (obj) {
  var ors = []; // tags for OR
  var tq = '';

  function el(s) {
    if(tq.length == 0) tq = tq+s;
    else tq = tq + '/' + s;
  }

  $(':checkbox').each(function(i, obj) {
    if($(obj).is(':checked')) {
      if(obj.getAttribute("id").startsWith("cbp")) ors.push(obj.getAttribute("title"));
      if(obj.getAttribute("id").startsWith("cba")) el(obj.getAttribute("title"));
      if(obj.getAttribute("id").startsWith("cbm")) el('-'+obj.getAttribute("title"));
    }
  });

  if(ors.length > 0) {
    var x = ors.reduce(function(acc,val){return acc.length <= 0 ? val : acc + '|' + val;})
    tq = tq.length <= 0 ? x : x + '/' + tq;
  }

//  $("#tagQuery").text(tq);
  $("#tagQuery").val(tq);
}

/** update the tag query from checkboxes */
function tagQueryTocb (tq) {
  $(':checkbox').prop('checked', false);

  function el(s) {
    var ors = s.split('|'); // or elements

    if(ors.length > 1) {
      ors.map(function(val) {
        $("#cbp"+val).prop('checked', true);
      })
    } else if(ors.length == 1) {
      var val = ors.pop()
      if(val.startsWith("-"))
        $("#cbm"+val.substr(1)).prop('checked', true);
      else
        $("#cba"+val).prop('checked', true);
    }
  }

  var ands = tq.split('/');

  if(ands.length > 0) {
    ands.map(el);
  }

}


