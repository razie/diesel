var diameter = 960;

var root;

var tree = d3.layout.tree()
    .size([360, diameter / 2 - 120])
    .separation(function(a, b) { return (a.parent == b.parent ? 1 : 2) / a.depth; });

var diagonal = d3.svg.diagonal.radial()
    .projection(function(d) { return [d.y, d.x / 180 * Math.PI]; });

var svg = d3.select("body").append("svg")
    .attr("width", diameter)
    .attr("height", diameter - 150)
  .append("g")
//    .call(d3.behavior.zoom().scaleExtent([1, 8]).on("zoom", zoom))
    .attr("transform", "translate(" + diameter / 2 + "," + diameter / 2 + ")");

d3.json(razUrl, function(error, json) {
  root = json
  var nodes = tree.nodes(root),
      links = tree.links(nodes);

  var link = svg.selectAll(".link")
      .data(links)
    .enter().append("path")
      .attr("class", "link")
      .attr("d", diagonal);

  var node = svg.selectAll(".node")
      .data(nodes)
    .enter().append("g")
      .attr("class", "node")
      .attr("transform", function(d) { return "rotate(" + (d.x - 90) + ")translate(" + d.y + ")"; });

  node.append("circle")
      .attr("r", 4.5);

  node.append("text")
      .attr("dy", ".31em")
      .attr("text-anchor", function(d) { return d.x < 180 ? "start" : "end"; })
      .attr("transform", function(d) { return d.x < 180 ? "translate(8)" : "rotate(180)translate(-8)"; })
      .text(function(d) { return d.name; })
      .on("click", function(x) {
         if(x.url===undefined) {} else
            window.location.href='http://localhost:9000/gapi/samples/g3?url='+x.url
       });

});

d3.select(self.frameElement).style("height", diameter - 150 + "px");

svg.selectAll("circle.node").on("click", function() {
   alert(d3.event.target);
//         d3.select(this).select("circle").style("fill","orange")
//       .style("stroke","orange");
});

function zoom() {
   svg.attr("transform", "translate(" + d3.event.translate + ")scale(" + d3.event.scale + ")");
 }

var odd=false;

var rotate = function() {
	  var nodes = tree.nodes(root),
      links = tree.links(nodes);

	  if(odd) {
//		  svg.selectAll(".node")[0].forEach(function(e){e.setAttribute("class", "xx")})
		  svg.selectAll(".node").select("circle").transition().style("fill", "yellow");
	} else {
		  svg.selectAll(".node").select("circle").transition().style("fill", "blue");
	}
odd=!odd;
};

setInterval(rotate, 3000);

