package com.iafenvoy.reincarnation.screen;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.parser.HTML;
import com.sighs.apricityui.resource.async.style.StyleAsyncHandler;
import com.sighs.apricityui.viewport.ApricityViewport;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

import java.util.Locale;
import java.util.Optional;

final class InformationElements {
    private InformationElements() {
    }

    static void prepare(String path) {
        if (HTML.preparedTemplateResources().stream().noneMatch(template -> template.path().equals(path)))
            HTML.reload(path);
        for (HTML.TemplateResources template : HTML.preparedTemplateResources()) {
            if (!template.path().equals(path)) continue;
            ApricityViewport viewport = ApricityViewport.spec(path).createState(path).resolve(Minecraft.getInstance().getWindow());
            StyleAsyncHandler.INSTANCE.warmUpTemplateStyles(path, template.externalStyleSrcs(), template.inlineStyles(),
                    new Size(viewport.layoutWidth(), viewport.layoutHeight()));
            break;
        }
    }

    static Element child(Element parent, String classes) {
        Element element = parent.appendChild(new Element(parent.getOwnerDocument(), "div"));
        element.setAttribute("class", classes);
        return element;
    }

    static Element require(Document document, String id) {
        Element element = document.getElementById(id);
        if (element == null) throw new IllegalStateException("Missing information page element: " + id);
        return element;
    }

    static void style(Element element, String property, String value) {
        if (!value.equals(element.getInlineStylePropertyValue(property)))
            element.setInlineStyleProperty(property, value);
    }

    static void bounds(Element element, int x, int y, int width, int height) {
        style(element, "left", x + "px");
        style(element, "top", y + "px");
        style(element, "width", width + "px");
        style(element, "height", height + "px");
    }

    static void visible(Element element, boolean visible) {
        style(element, "display", visible ? "" : "none");
    }

    static void disabled(Element element, boolean disabled) {
        if (element.hasAttribute("disabled") != disabled) element.setDisabled(disabled);
    }

    static void token(Element element, String token, boolean value) {
        if (element.getClassList().contains(token) != value) element.getClassList().toggle(token, value);
    }

    static void text(Element element, FormattedText text) {
        if (element.getChildren().isEmpty() && !element.getTextContent().isEmpty()) element.setTextContent("");
        int[] index = {0};
        text.visit((style, contents) -> {
            if (contents.isEmpty()) return Optional.empty();
            Element run = index[0] < element.getChildren().size() ? element.getChildren().get(index[0])
                    : element.appendChild(new Element(element.getOwnerDocument(), "span"));
            if (!contents.equals(run.getTextContent())) run.setTextContent(contents);
            style(run, "white-space", "pre");
            style(run, "flex-shrink", "0");
            style(run, "color", style.getColor() == null ? "inherit" : String.format(Locale.ROOT, "#%06X", style.getColor().getValue()));
            style(run, "font-weight", style.isBold() ? "bold" : "normal");
            style(run, "font-style", style.isItalic() ? "oblique" : "normal");
            String decoration = (style.isUnderlined() ? "underline " : "") + (style.isStrikethrough() ? "line-through" : "");
            style(run, "text-decoration-line", decoration.isBlank() ? "none" : decoration.trim());
            index[0]++;
            return Optional.empty();
        }, Style.EMPTY);
        while (element.getChildren().size() > index[0]) element.removeChild(element.getLastElementChild());
    }
}
