function escapeHtml(str) {
	var div = document.createElement('div');
	div.appendChild(document.createTextNode(str));
	return div.innerHTML;
};

function appendRemoteOutputToLine(line, text, checkClass, lineClass, append) {
	lh = cm.getLineHandle(line)
	w = cm.lineInfo(lh).widgets
	found = null;

	if (w)
		for (var i = 0; i < w.length; i++) {
			var ele = $(w[i].node)
			if (ele.hasClass(checkClass) || ele.hasClass(lineClass)) {
				if (!append) {
					w[i].clear();
					i--;
				} else {
					append = false;
					found = w[i];
				}
			}
		}

	var bm;

	if (found != null) {
		d = found.node
		d.lastOutputAt = new Date().valueOf();

		$(d).removeClass(checkClass)
		$(d).removeClass(lineClass)
		$(d).addClass(lineClass)

		$(d).append("\n" + text.trim())

		$(d).animate({
			scrollTop: $(d)[0].scrollHeight
		}, 0)

		$(d).css({
			opacity: 1
		})
		$(d).stop().animate({
			opacity: 0.75
		}, 500)
		found.changed()

		$(d).scrollTop($(d)[0].scrollHeight)

	} else {
		d = $("<div class='" + lineClass + "'><div class='Field-closebox'>&#x2715;</div><div class='Field-expandBox'>&#x21A7;</div>" + text.trim() + "</div>")[0]
		d.lastOutputAt = new Date().valueOf();

		bm = cm.addLineWidget(lh, d, {
			showIfHidden: true,
			handleMouseEvents: false
		})
		d.bm = bm

		if (text.trim().length < 1 || text.trim() == "&#10003;") {
			$(d).animate({opacity: 0.0, "max-height": "0%"}, {
				"duration": 400, "progress": function () {
					bm.changed()
				}, "done": function () {
					bm.clear();
					updateAllBrackets();
				}
			});
		}
		else {
			setTimeout(function () {
				$(d).scrollTop($(d)[0].scrollHeight)
			},0);
		}

	}

	var thisDiv = $(d)
	var closeBox = $($(d).children()[0])
	var expandBox = $($(d).children()[1])

	updateAllBrackets()

	if (bm) {
		closeBox.click(function () {
			bm.clear()
			updateAllBrackets()
		})
		closeBox.hover(function () {
			closeBox.css("color", "#f55")
		}, function () {
			closeBox.css("color", "#fff")
		})
		expandBox.hover(function () {
			$(this).css("color", "#ff5")
		}, function () {
			$(this).css("color", "#fff")
		})
		expandBox.click(function () {
			if (thisDiv.maxSize().height > 2000) {
				thisDiv.css("max-height", "50")
				expandBox.html("&#x21A7;")
				updateAllBrackets()
			} else {
				thisDiv.css("max-height", "2500")
				expandBox.html("&#x21A5;")
				updateAllBrackets()
			}
		})
	}

}

function serializeAllOutput() {
	ret = ""

	for (var line = 0; line < cm.lineCount(); line++) {
		w = cm.lineInfo(line).widgets

		if (w)
			for (var i = 0; i < w.length; i++) {
				var ele = $(w[i].node)
				if (ele.hasClass("Field-remoteOutput")) {
					text = ele.clone().find(".Field-closebox").remove().end().find(".Field-expandBox").remove().end()
					if ("html" in text) {
						text = $(text).html();
					}
					else if ("text" in text) {
						text = text.text()
					}
					else {
						text = "";
					}
					ret += "appendRemoteOutputToLine(" + line + ", " + JSON.stringify(text) + ", 'Field-remoteOutput-error', 'Field-remoteOutput', false)\n";
				}
				if (ele.hasClass("Field-remoteOutput-error")) {
					text = ele.clone().find(".Field-closebox").remove().end().find(".Field-expandBox").remove().end()
					if ("html" in text) {
						text = $(text).html();
					}
					else if ("text" in text) {
						text = text.text()
					}
					else {
						text = "";
					}
					ret += "appendRemoteOutputToLine(" + line + ", " + JSON.stringify(text) + ", 'Field-remoteOutput', 'Field-remoteOutput-error', false)\n";
				}
			}
	}
	return ret;
}

function clearOutputs(startLine, endLine) {
	for (var line = startLine; line < endLine; line++) {
		w = cm.lineInfo(line).widgets

		if (w)
			for (var i = 0; i < w.length; i++) {
				var ele = $(w[i].node)
				if (ele.hasClass("Field-remoteOutput") || ele.hasClass("Field-remoteOutput-error")) {
					w[i].clear();
				}
			}
	}
	updateAllBrackets();
}

globalCommands.push({
	"name": "Clear Outputs",
	"info": "Clear all output areas in this editor.",
	"callback": function () {
		clearOutputs(0, cm.lineCount())
	}
});

boxOutputs = {}

_messageBus.subscribe("box.output", function (d, e) {
	box = d.box
	if (cm.currentbox === box) {
		appendRemoteOutputToLine(cm.lineCount() - 1, d.message, "Field-remoteOutput-error", "Field-remoteOutput", true)
	} else {
	}
	if (boxOutputs[box] === undefined)
		boxOutputs[box] = d.message
	else
		boxOutputs[box] += "\n" + d.message
})

_messageBus.subscribe("box.error", function (d, e) {
	box = d.box
	if (cm.currentbox === box) {
		appendRemoteOutputToLine(cm.lineCount() - 1, d.message, "Field-remoteOutput", "Field-remoteOutput-error", true)
	} else {
	}
	if (boxOutputs[box] === undefined)
		boxOutputs[box] = d.message
	else
		boxOutputs[box] += "\n" + d.message
})