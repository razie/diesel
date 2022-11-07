/**
 *
 * Created by razvanc on 18/06/2014.
 */

/* draw axis on a raphael canvas
  r - the Raphael canvas to draw on
  grid - true/false draws a grid or not
  offset - how far from 0,0 to draw the axis - they show better at 2
*/
var axis = function (r, grid, offset) {
  var g = grid || false;
  var o = offset || 2;

  var w=r.width;
  var h=r.height;

  r.path("M"+w+","+o+"L0,"+o+"L"+o+","+h).attr("stroke", "red");
  r.path("M"+w+","+o+"L"+(w-5)+",5").attr("stroke", "red");
  r.path("M"+o+","+h+"L5,"+(h-5)).attr("stroke", "red");

  var len = grid ? h : 10;
  for (var i=1; i<=w/50; i=i+1) {
    r.path("M"+i*50+",0L"+i*50+","+len).attr("stroke", "gray");
    if(i%2 == 0) r.text(i*50-10,10,i*50).attr("fill", "blue");
  }

  var len = grid ? w : 10
  for (var i=1; i<=h/50; i=i+1) {
    r.path("M0,"+i*50+"L"+len+","+i*50).attr("stroke", "gray");
    if(i%2 == 0) r.text(10,i*50-10,i*50).attr("fill", "blue");
  }
}

