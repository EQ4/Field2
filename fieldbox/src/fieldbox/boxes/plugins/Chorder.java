package fieldbox.boxes.plugins;

import field.graphics.FLine;
import field.graphics.Window;
import field.linalg.Vec2;
import field.linalg.Vec4;
import field.utility.Pair;
import field.utility.Rect;
import field.utility.Triple;
import fieldbox.boxes.*;
import fielded.Execution;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by marc on 4/16/14.
 */
public class Chorder extends Box {

	public Chorder(Box root_unused) {

		properties.putToList(Mouse.onMouseDown, (e, button) -> {

			System.out.println(" on mouse down :" + e + " " + button + " " + e.after.keyboardState.keysDown + " " + e.after.keyboardState
				    .isAltDown());

			if (button != 0) return null;
			if (!e.after.keyboardState.isAltDown()) return null;
			if (e.after.keyboardState.isShiftDown()) return null;
			if (e.after.keyboardState.isSuperDown()) return null;
			if (e.after.keyboardState.isControlDown()) return null;

			Optional<Drawing> drawing = this.find(Drawing.drawing, both()).findFirst();
			Vec2 point = drawing.map(x -> x.windowSystemToDrawingSystem(new Vec2(e.after.x, e.after.y)))
				    .orElseThrow(() -> new IllegalArgumentException(" cant mouse around something without drawing support (to provide coordinate system)"));

			Stream<Pair<Box, Rect>> fr = breadthFirst(both()).map(b -> new Pair<>(b, frame(b))).filter(b -> b.second != null);

			List<Pair<Box, Rect>> frames = fr.collect(Collectors.toList());

			Optional<Pair<Box, Rect>> hit = frames.stream().filter(b -> b.second.intersects(point))
				    .sorted((a, b) -> Float.compare(order(a.second), order(b.second))).findFirst();

			System.out.println(" chord hit :" + hit);

			if (hit.isPresent()) return executeNowAt(e, point, hit.get().first);

			// we have an execution chord

			return chordAt(frames, point, e);
		});

	}

	private Mouse.Dragger executeNowAt(Window.Event<Window.MouseState> e, Vec2 point, Box box) {
		e.properties.put(Window.consumed, true);

		properties.putToMap(FLineDrawing.frameDrawing, "__feedback__chorderbox", FLineDrawing.expires(b -> {

			FLine f = new FLine();
			Rect fr = frame(box);
			f.rect(fr.x, fr.y, fr.w, fr.h);

			f.attributes.put(FLineDrawing.strokeColor, new Vec4(0.5f, 0.75f, 0.5f, -0.5f));
			f.attributes.put(FLineDrawing.thicken, new BasicStroke(10.5f));

			return f;

		}, 50));

		int count0 = box.properties.computeIfAbsent(IsExecuting.executionCount, (k) -> 0);

		if (count0 == 0) {

			box.first(Execution.execution).ifPresent(x -> x.support(box, Execution.code).begin(box));
			// with remote back ends it's possible we'll have to defer this to the next update cycle to give them a chance to acknowledge that were actually executing
			int count1 = box.properties.computeIfAbsent(IsExecuting.executionCount, (k) -> 0);

			if (count1 > count0) {
				MarkingMenus.MenuSpecification menuSpec = new MarkingMenus.MenuSpecification();
				menuSpec.items.put(MarkingMenus.Position.NH, new MarkingMenus.MenuItem("Continue", () -> {
					System.out.println(" continue !");
				}));
				menuSpec.nothing = () -> {
					box.first(Execution.execution).ifPresent(x -> x.support(box, Execution.code).end(box));
				};
				return MarkingMenus.runMenu(box, point, menuSpec);
			}
		} else {
			MarkingMenus.MenuSpecification menuSpec = new MarkingMenus.MenuSpecification();
			menuSpec.items.put(MarkingMenus.Position.SH, new MarkingMenus.MenuItem("Stop", () -> {
				box.first(Execution.execution).ifPresent(x -> x.support(box, Execution.code).end(box));
			}));
			return MarkingMenus.runMenu(box, point, menuSpec);
		}

		return null;
	}

	private Mouse.Dragger chordAt(List<Pair<Box, Rect>> frames, Vec2 start, Window.Event<Window.MouseState> initiation) {

		boolean[] once = {false};

		return (e, end) -> {

			Optional<Drawing> drawing = this.find(Drawing.drawing, both()).findFirst();
			Vec2 point = drawing.map(x -> x.windowSystemToDrawingSystem(new Vec2(e.after.x, e.after.y)))
				    .orElseThrow(() -> new IllegalArgumentException(" cant mouse around something without drawing support (to provide coordinate system)"));

			chordOver(frames, start, point, end);

			if (end && !once[0]) {

				List<Triple<Vec2, Float, Box>> intersections = intersectionsFor(frames, start, point);

				once[0] = true;
				for (int i = 0; i < intersections.size(); i++) {
					if (intersections.get(i) == null) continue;

					Box b = intersections.get(i).third;

					b.first(Execution.execution).ifPresent(x -> x.support(b, Execution.code).begin(b));
				}
			}

			return !end;
		};

	}

	protected float order(Rect r) {
		return Math.abs(r.w) + Math.abs(r.h);
	}

	private void chordOver(List<Pair<Box, Rect>> frames, Vec2 start, Vec2 end, boolean termination) {


		properties.putToMap(FLineDrawing.frameDrawing, "__feedback__chorder", FLineDrawing.expires(box -> {
			FLine f = new FLine();
			f.moveTo(start.x, start.y, 0);
			f.lineTo(end.x, end.y, 0);
			f.attributes.put(FLineDrawing.color, new Vec4(0.5f, 0.95f, 0.6f, 0.15f));
			f.attributes.put(FLineDrawing.thicken, new BasicStroke(3.5f));

			return f;
		}, termination ? 50 : -1));
		properties.putToMap(FLineDrawing.frameDrawing, "__feedback__chorderC", FLineDrawing.expires(box -> {
			FLine f = new FLine();
			f.moveTo(start.x, start.y, 0);
			f.lineTo(end.x, end.y, 0);
			f.attributes.put(FLineDrawing.color, new Vec4(0.5f, 0.95f, 0.6f, 0.5f));
			f.attributes.put(FLineDrawing.thicken, new BasicStroke(1.5f));

			return f;
		}, termination ? 50 : -1));

		List<Triple<Vec2, Float, Box>> i = intersectionsFor(frames, start, end);
		properties.putToMap(FLineDrawing.frameDrawing, "__feedback__chorderbox", FLineDrawing.expires(box -> {

			FLine f = new FLine();

			i.stream().filter(x -> x != null).forEach((x) -> {

				Rect fr = frame(x.third);

				f.rect(fr.x, fr.y, fr.w, fr.h);


			});

			f.attributes.put(FLineDrawing.strokeColor, new Vec4(0.5f, 0.75f, 0.5f, -0.5f));
			f.attributes.put(FLineDrawing.thicken, new BasicStroke(10.5f));

			return f;

		}, termination ? 50 : -1));
		properties.putToMap(FLineDrawing.frameDrawing, "__feedback__chorderbox2", FLineDrawing.expires(box -> {

			FLine f = new FLine();

			i.stream().filter(x -> x != null).forEach((x) -> {

				Rect fr = frame(x.third);

				float w = 8;
				f.rect(x.first.x - w, x.first.y - w, w * 2, w * 2);

			});

			f.attributes.put(FLineDrawing.color, new Vec4(0.5f, 0.75f, 0.5f, -0.75f));
			f.attributes.put(FLineDrawing.filled, true);
			f.attributes.put(FLineDrawing.stroked, false);
			return f;

		}, termination ? 50 : -1));

		properties.putToMap(FLineDrawing.frameDrawing, "__feedback__chorderbox3", FLineDrawing.expires(box -> {

			FLine f = new FLine();

			int[] count = {1};

			Vec2 delta = new Vec2(end.y - start.y, start.x - end.x);
			delta.normalise();
			i.stream().filter(x -> x != null).forEach((x) -> {

				f.moveTo(x.first.x + delta.x * 12, x.first.y + delta.y * 12);
				f.nodes.get(f.nodes.size() - 1).attributes.put(FLineDrawing.text, " " + (count[0]++));
			});

			f.attributes.put(FLineDrawing.color, new Vec4(0.1f, 0.25f, 0.1f, 0.75f));
			f.attributes.put(FLineDrawing.hasText, true);
			return f;

		}, termination ? 50 : -1));


		Drawing.dirty(this);

	}

	protected Rect frame(Box hitBox) {
		return hitBox.properties.get(frame);
	}


	public List<Triple<Vec2, Float, Box>> intersectionsFor(List<Pair<Box, Rect>> frames, Vec2 start, Vec2 end) {
		List<Triple<Vec2, Float, Box>> ret = new ArrayList<>();

		for (Pair<Box, Rect> br : frames) {
			Rect r = br.second;

			Vec2 v1 = getLineIntersection(new Vec2(r.x, r.y), new Vec2(r.x + r.w, r.y), start, end);
			Vec2 v2 = getLineIntersection(new Vec2(r.x, r.y + r.h), new Vec2(r.x + r.w, r.y + r.h), start, end);
			Vec2 v3 = getLineIntersection(new Vec2(r.x, r.y + r.h), new Vec2(r.x, r.y), start, end);
			Vec2 v4 = getLineIntersection(new Vec2(r.x + r.w, r.y), new Vec2(r.x + r.w, r.y + r.h), start, end);
			List<Vec2> al = Arrays.asList(v1, v2, v3, v4);
			Collections.sort(al, (a, b) -> {
				if (a == null) return b == null ? 0 : 1;
				if (b == null) return -1;
				return Double.compare(a.distanceFrom(start), b.distanceFrom(start));
			});

			if (al.get(0) == null) {
				ret.add(null);
				continue;
			}

			ret.add(new Triple<>(al.get(0), (float) al.get(0).distanceFrom(start), br.first));
		}

		return ret;
	}

	public Vec2 getLineIntersection(Vec2 p0, Vec2 p1, Vec2 p2, Vec2 p3) {
		float s02_x, s02_y, s10_x, s10_y, s32_x, s32_y, s_numer, t_numer, denom, t;
		s10_x = p1.x - p0.x;
		s10_y = p1.y - p0.y;
		s32_x = p3.x - p2.x;
		s32_y = p3.y - p2.y;

		denom = s10_x * s32_y - s32_x * s10_y;
		if (denom == 0) return null;

		boolean denomPositive = denom > 0;

		s02_x = p0.x - p2.x;
		s02_y = p0.y - p2.y;
		s_numer = s10_x * s02_y - s10_y * s02_x;
		if ((s_numer < 0) == denomPositive) return null;

		t_numer = s32_x * s02_y - s32_y * s02_x;
		if ((t_numer < 0) == denomPositive) return null;

		if (((s_numer > denom) == denomPositive) || ((t_numer > denom) == denomPositive)) return null;

		t = t_numer / denom;
		return new Vec2(p0.x + (t * s10_x), p0.y + (t * s10_y));
	}
}
