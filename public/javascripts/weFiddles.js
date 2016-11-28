/**
 * Created by razvanc on 02/05/2014.
 */

var razuri = function(s) {
  return encodeURI(s.replace(/;/g,'%3B').replace(/\+/g,'%2B').replace(/script/g,'scrRAZipt'));
};

var encscr = function(s) {
  return s.replace(/'/g,'%27');
};

var decscr = function (s) {
  return s.replace(/%27/g,'\'');
};

/** Enhanced from http://cwestblog.com/2016/03/16/javascript-detecting-infinite-loops/
 *
 * See
 *
 * @param code
 * @param fnOnStop
 * @param opt_timeoutInMS
 * @param output
 */
function limitEval(code, fnOnStop, opt_timeoutInMS, output) {
  var id = Math.random() + 1;

  var script = 'onmessage=function(a){' +
  'output="";'+
  'preambleData=a.data;' +
  'a=a.data;' +
  'postMessage({i:a.i+1});' +
  'var e;' +
  'var x;' +
  'try {' +
  '  x = eval.call(this, a.c);' +
  '} catch (ex) {' +
  '  console.log(ex);' +
  '  e = ex.toString();' +
  '};' +
  'postMessage({' +
  '  r:x,' +
  '  o:output,' +
  '  e:e,' +
  '  i:a.i' +
  '})};' +
  '';
  var blob = new Blob([script], { type:'text/javascript' } ),
    myWorker = new Worker(URL.createObjectURL(blob));

  function onDone() {
    URL.revokeObjectURL(blob);
    fnOnStop.apply(this, arguments);
  }

  myWorker.onmessage = function (data) {
    data = data.data;
    if (data) {
      if (data.i === id) {
        if(typeof data.cmd != "undefined") {
          if(data.cmd == 'plot') {
            window.raphael.rect(data.x, data.y, 1, 1).attr({"fill": data.c, "stroke": data.c});
          } else if(data.cmd == 'rect') {
            window.raphael.rect(data.x, data.y, data.l, data.w).attr({"fill": data.c, "stroke": data.c});
          }
        } else if(typeof data.e != "undefined") {
          id = 0;
          onDone(false, data.e);
          myWorker.terminate();
        } else if(typeof data.r != "undefined" || typeof data.o != "undefined" && data.o != "") {
          id = 0;
          onDone(true, data.r,data.o);
          myWorker.terminate();
        } else {
          if(typeof data.o != "undefined" && data.o != "") {
            if(typeof output != "undefined") {
             output = output+data.o+"\n";
            }
          }
        }
      } else if (data.i === id + 1) {
        setTimeout(function() {
          if (id) {
            myWorker.terminate();
            onDone(false);
          }
        }, opt_timeoutInMS || 1000);
      }
    }
  };

  myWorker.postMessage({ c: 'var id='+id+';\n'+code, i: id });
}


