package io.github.caspe.glib.api.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Environment(EnvType.CLIENT)
public final class GlibWorkTree {
	private final Map<String, Point> cardPositions = new LinkedHashMap<>();
	private final List<Link> links = new ArrayList<>();
	private double panX = 24.0D;
	private double panY = 32.0D;
	private double zoom = 1.0D;
	private double minZoom = 0.55D;
	private double maxZoom = 1.8D;
	private PanDrag panDrag;
	private CardDrag cardDrag;
	private LinkDrag linkDrag;

	public double panX() {
		return panX;
	}

	public double panY() {
		return panY;
	}

	public double zoom() {
		return zoom;
	}

	public GlibWorkTree zoomRange(double minZoom, double maxZoom) {
		this.minZoom = Math.max(0.05D, Math.min(minZoom, maxZoom));
		this.maxZoom = Math.max(this.minZoom, maxZoom);
		zoom = clamp(zoom, this.minZoom, this.maxZoom);
		return this;
	}

	public void setPan(double panX, double panY) {
		this.panX = panX;
		this.panY = panY;
	}

	public void setZoom(double zoom) {
		this.zoom = clamp(zoom, minZoom, maxZoom);
	}

	public void clear() {
		cardPositions.clear();
		links.clear();
		panDrag = null;
		cardDrag = null;
		linkDrag = null;
	}

	public Map<String, Point> cardPositions() {
		return Map.copyOf(cardPositions);
	}

	public List<Link> links() {
		return List.copyOf(links);
	}

	public Point cardPosition(String cardKey, Point fallback) {
		return cardPositions.getOrDefault(cardKey, fallback);
	}

	public void setCardPosition(String cardKey, Point position) {
		if (cardKey == null || cardKey.isBlank() || position == null) {
			return;
		}
		cardPositions.put(cardKey, position);
	}

	public boolean hasCard(String cardKey) {
		return cardPositions.containsKey(cardKey);
	}

	public void removeCard(String cardKey) {
		cardPositions.remove(cardKey);
		links.removeIf(link -> link.from().equals(cardKey) || link.to().equals(cardKey));
	}

	public void retainCards(Iterable<String> validKeys) {
		List<String> valid = new ArrayList<>();
		for (String key : validKeys) {
			valid.add(key);
		}
		cardPositions.keySet().removeIf(key -> !valid.contains(key));
		links.removeIf(link -> !cardPositions.containsKey(link.from()) || !cardPositions.containsKey(link.to()));
	}

	public void addLink(String from, String to) {
		if (from == null || to == null || from.equals(to)) {
			return;
		}
		links.removeIf(link -> link.from().equals(from) && link.to().equals(to));
		links.add(new Link(from, to));
	}

	public void removeLink(String from, String to) {
		links.removeIf(link -> link.from().equals(from) && link.to().equals(to));
	}

	public Point screenToWorld(GlibRect canvas, double screenX, double screenY) {
		return new Point(
			(screenX - canvas.x() - panX) / zoom,
			(screenY - canvas.y() - panY) / zoom
		);
	}

	public Point worldToScreen(GlibRect canvas, Point world) {
		return new Point(
			canvas.x() + panX + world.x() * zoom,
			canvas.y() + panY + world.y() * zoom
		);
	}

	public GlibRect worldRectToScreen(GlibRect canvas, GlibRect worldRect) {
		Point screen = worldToScreen(canvas, new Point(worldRect.x(), worldRect.y()));
		return new GlibRect(
			(int) Math.round(screen.x()),
			(int) Math.round(screen.y()),
			Math.max(1, (int) Math.round(worldRect.width() * zoom)),
			Math.max(1, (int) Math.round(worldRect.height() * zoom))
		);
	}

	public void zoomAt(GlibRect canvas, double screenX, double screenY, double amount) {
		double factor = amount > 0.0D ? 1.10D : 0.90D;
		Point before = screenToWorld(canvas, screenX, screenY);
		double previousZoom = zoom;
		zoom = clamp(zoom * factor, minZoom, maxZoom);
		if (Math.abs(previousZoom - zoom) < 0.001D) {
			return;
		}
		panX = screenX - canvas.x() - before.x() * zoom;
		panY = screenY - canvas.y() - before.y() * zoom;
	}

	public void beginPan(double mouseX, double mouseY) {
		panDrag = new PanDrag(mouseX, mouseY, panX, panY);
	}

	public boolean updatePan(double mouseX, double mouseY) {
		if (panDrag == null) {
			return false;
		}
		panX = panDrag.startPanX() + mouseX - panDrag.mouseX();
		panY = panDrag.startPanY() + mouseY - panDrag.mouseY();
		return true;
	}

	public void endPan() {
		panDrag = null;
	}

	public boolean panning() {
		return panDrag != null;
	}

	public void beginCardDrag(String cardKey, Point worldMouse) {
		Point position = cardPositions.getOrDefault(cardKey, new Point(0.0D, 0.0D));
		cardDrag = new CardDrag(cardKey, worldMouse.x() - position.x(), worldMouse.y() - position.y());
	}

	public boolean updateCardDrag(Point worldMouse) {
		if (cardDrag == null) {
			return false;
		}
		setCardPosition(cardDrag.cardKey(), new Point(worldMouse.x() - cardDrag.offsetX(), worldMouse.y() - cardDrag.offsetY()));
		return true;
	}

	public CardDrag endCardDrag() {
		CardDrag drag = cardDrag;
		cardDrag = null;
		return drag;
	}

	public CardDrag cardDrag() {
		return cardDrag;
	}

	public void beginLinkDrag(String fromKey, Point worldMouse) {
		linkDrag = new LinkDrag(fromKey, worldMouse);
	}

	public void updateLinkDrag(Point worldMouse) {
		if (linkDrag != null) {
			linkDrag = new LinkDrag(linkDrag.fromKey(), worldMouse);
		}
	}

	public LinkDrag endLinkDrag() {
		LinkDrag drag = linkDrag;
		linkDrag = null;
		return drag;
	}

	public LinkDrag linkDrag() {
		return linkDrag;
	}

	private double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	public record Point(double x, double y) {
	}

	public record Link(String from, String to) {
	}

	public record CardDrag(String cardKey, double offsetX, double offsetY) {
	}

	public record LinkDrag(String fromKey, Point mouseWorld) {
	}

	private record PanDrag(double mouseX, double mouseY, double startPanX, double startPanY) {
	}
}
