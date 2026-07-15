package io.github.caspe.glib.api.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public final class GlibConfigScreen extends Screen {
	private final Screen parent;
	private final List<Option> options;

	private GlibConfigScreen(Screen parent, Text title, List<Option> options) {
		super(title);
		this.parent = parent;
		this.options = options;
	}

	public static Builder builder(Screen parent, Text title) {
		return new Builder(parent, title);
	}

	@Override
	protected void init() {
		int width = 260;
		int x = this.width / 2 - width / 2;
		int y = Math.max(36, this.height / 2 - options.size() * 14);
		for (Option option : options) {
			addDrawableChild(option.create(x, y, width));
			y += 28;
		}
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
			.dimensions(this.width / 2 - 100, this.height - 28, 200, 20)
			.build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, width, height, 0xB0060A10);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFFFF);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}

	public static final class Builder {
		private final Screen parent;
		private final Text title;
		private final List<Option> options = new ArrayList<>();

		private Builder(Screen parent, Text title) {
			this.parent = parent;
			this.title = title;
		}

		public Builder action(Text message, Runnable action) {
			options.add((x, y, width) -> ButtonWidget.builder(message, button -> action.run())
				.dimensions(x, y, width, 20)
				.build());
			return this;
		}

		public Builder cycle(Supplier<Text> message, Runnable cycle) {
			options.add((x, y, width) -> ButtonWidget.builder(message.get(), button -> {
				cycle.run();
				button.setMessage(message.get());
			}).dimensions(x, y, width, 20).build());
			return this;
		}

		public Builder slider(DoubleSupplier value, DoubleConsumer setter, DoubleFunction<Text> message) {
			options.add((x, y, width) -> new ConfigSlider(x, y, width, 20, value, setter, message));
			return this;
		}

		public GlibConfigScreen build() {
			return new GlibConfigScreen(parent, title, List.copyOf(options));
		}
	}

	private interface Option {
		net.minecraft.client.gui.widget.ClickableWidget create(int x, int y, int width);
	}

	private static final class ConfigSlider extends SliderWidget {
		private final DoubleConsumer setter;
		private final DoubleFunction<Text> message;

		private ConfigSlider(int x, int y, int width, int height, DoubleSupplier value, DoubleConsumer setter, DoubleFunction<Text> message) {
			super(x, y, width, height, message.apply(value.getAsDouble()), clamp01(value.getAsDouble()));
			this.setter = setter;
			this.message = message;
		}

		@Override
		protected void updateMessage() {
			setMessage(message.apply(value));
		}

		@Override
		protected void applyValue() {
			setter.accept(value);
		}

		private static double clamp01(double value) {
			return Math.max(0.0D, Math.min(1.0D, value));
		}
	}
}
