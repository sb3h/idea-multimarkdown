/*
 * Copyright (c) 2011-2014 Julien Nicoulaud <julien.nicoulaud@gmail.com>
* Copyright (c) 2015 Vladimir Schneider <vladimir.schneider@gmail.com>
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.vladsch.idea.multimarkdown.editor;

import com.intellij.codeHighlighting.BackgroundEditorHighlighter;
import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorLocation;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.FileEditorStateLevel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.ui.components.JBScrollPane;

import java.io.IOException;
import java.io.StringReader;
import java.lang.String;

import com.vladsch.idea.multimarkdown.MarkdownBundle;
import com.vladsch.idea.multimarkdown.settings.MarkdownGlobalSettings;
import com.vladsch.idea.multimarkdown.settings.MarkdownGlobalSettingsListener;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.pegdown.PegDownProcessor;

import javax.swing.*;
import java.util.Timer;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link FileEditor} implementation that provides rendering preview for Markdown documents.
 * <p/>
 * The preview is generated by <a href="https://github.com/sirthias/pegdown">pegdown</a>.
 *
 * @author Vladimir Schneider <vladimir.schneider@gmail.com>
 * @author Julien Nicoulaud <julien.nicoulaud@gmail.com>
 * @author Roger Grantham (https://github.com/grantham)
 * @see <a href="https://github.com/sirthias/pegdown">pegdown library</a>
 * @see MarkdownPreviewEditorProvider
 * @since 0.1
 */
public class MarkdownPreviewEditor extends UserDataHolderBase implements FileEditor {

    /** Logger. */
    private static final Logger LOGGER = Logger.getInstance(MarkdownPreviewEditor.class);

    /** The editor name, displayed as the tab name of the editor. */
    public static final String EDITOR_NAME = MarkdownBundle.message("markdown.editor.preview.tab-name");

    /** The path to the stylesheet used for displaying the HTML preview of the document. */
    @NonNls
    public static final String PREVIEW_STYLESHEET_PATH = "/com/vladsch/idea/multimarkdown/preview.css";

    /** The {@link java.awt.Component} used to render the HTML preview. */
    protected final JEditorPane jEditorPane = new JEditorPane();

    /** The {@link JBScrollPane} allowing to browse {@link #jEditorPane}. */
    protected final JBScrollPane scrollPane = new JBScrollPane(jEditorPane);

    /** The {@link Document} previewed in this editor. */
    protected final Document document;

    protected MarkdownGlobalSettingsListener globalSettingsListener;

    /** The {@link PegDownProcessor} used for building the document AST. */
    private ThreadLocal<PegDownProcessor> processor = initProcessor();

    private boolean isActive = false;

    /** Init/reinit thread local {@link PegDownProcessor}. */
    private static ThreadLocal<PegDownProcessor> initProcessor() {
        return new ThreadLocal<PegDownProcessor>() {
            @Override protected PegDownProcessor initialValue() {
                return new PegDownProcessor(MarkdownGlobalSettings.getInstance().getExtensionsValue(),
                                            MarkdownGlobalSettings.getInstance().getParsingTimeout());
            }
        };
    }

    /** Indicates whether the HTML preview is obsolete and should regenerated from the Markdown {@link #document}. */
    protected boolean previewIsObsolete = true;

    protected Timer updateDelayTimer;

    protected int updateDelay = MarkdownGlobalSettings.getInstance().getUpdateDelay();

    /**
     * Build a new instance of {@link MarkdownPreviewEditor}.
     *
     * @param project  the {@link Project} containing the document
     * @param document the {@link com.intellij.openapi.editor.Document} previewed in this editor.
     */
    public MarkdownPreviewEditor(@NotNull Project project, @NotNull Document document) {
        this.document = document;

        // Listen to the document modifications.
        this.document.addDocumentListener(new DocumentAdapter() {
            @Override
            public void documentChanged(DocumentEvent e) {
                delayedHtmlPreviewUpdate(false);
            }
        });

        // Listen to settings changes
        MarkdownGlobalSettings.getInstance().addListener(globalSettingsListener = new MarkdownGlobalSettingsListener() {
            public void handleSettingsChanged(@NotNull final MarkdownGlobalSettings newSettings) {
                updateDelay = MarkdownGlobalSettings.getInstance().getUpdateDelay();
                delayedHtmlPreviewUpdate(true);
            }
        });

        // Setup the editor pane for rendering HTML.
        setStyleSheet();
        jEditorPane.setEditable(false);

        // Set the editor pane position to top left, and do not let it reset it
        jEditorPane.getCaret().setMagicCaretPosition(new Point(0, 0));
        ((DefaultCaret) jEditorPane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        // Add a custom link listener which can resolve local link references.
        jEditorPane.addHyperlinkListener(new MarkdownLinkListener(jEditorPane, project, document));
    }

    protected void delayedHtmlPreviewUpdate(final boolean fullKit) {
        if (updateDelayTimer != null) {
            updateDelayTimer.cancel();
            updateDelayTimer = null;
        }

        updateDelayTimer = new Timer();
        updateDelayTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                ApplicationManager.getApplication().invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        previewIsObsolete = true;

                        if (fullKit) {
                            setStyleSheet();
                            processor.remove();     // make it re-initialize when accessed
                        }

                        updateHtmlContent(true);
                    }
                }, ModalityState.any());
            }
        }, updateDelay);
    }

    protected void setStyleSheet() {
        MarkdownEditorKit htmlKit = new MarkdownEditorKit(document);

        final StyleSheet style = new StyleSheet();
        String customCss;

        if ((customCss = MarkdownGlobalSettings.getInstance().getCustomCss()).equals("")) {
            style.importStyleSheet(MarkdownPreviewEditor.class.getResource(PREVIEW_STYLESHEET_PATH));
        } else {
            try {
                style.loadRules(new StringReader(customCss), null);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        htmlKit.setStyleSheet(style);

        jEditorPane.setEditorKit(htmlKit);
    }

    /**
     * Get the {@link java.awt.Component} to display as this editor's UI.
     *
     * @return a scrollable {@link JEditorPane}.
     */
    @NotNull
    public JComponent getComponent() {
        return scrollPane;
    }

    /**
     * Get the component to be focused when the editor is opened.
     *
     * @return {@link #scrollPane}
     */
    @Nullable
    public JComponent getPreferredFocusedComponent() {
        return scrollPane;
    }

    /**
     * Get the editor displayable name.
     *
     * @return {@link #EDITOR_NAME}
     */
    @NotNull
    @NonNls
    public String getName() {
        return EDITOR_NAME;
    }

    /**
     * Get the state of the editor.
     * <p/>
     * Just returns {@link FileEditorState#INSTANCE} as {@link MarkdownPreviewEditor} is stateless.
     *
     * @param level the level.
     * @return {@link FileEditorState#INSTANCE}
     * @see #setState(com.intellij.openapi.fileEditor.FileEditorState)
     */
    @NotNull
    public FileEditorState getState(@NotNull FileEditorStateLevel level) {
        return FileEditorState.INSTANCE;
    }

    /**
     * Set the state of the editor.
     * <p/>
     * Does not do anything as {@link MarkdownPreviewEditor} is stateless.
     *
     * @param state the new state.
     * @see #getState(com.intellij.openapi.fileEditor.FileEditorStateLevel)
     */
    public void setState(@NotNull FileEditorState state) {
    }

    /**
     * Indicates whether the document content is modified compared to its file.
     *
     * @return {@code false} as {@link MarkdownPreviewEditor} is read-only.
     */
    public boolean isModified() {
        return false;
    }

    /**
     * Indicates whether the editor is valid.
     *
     * @return {@code true} if {@link #document} content is readable.
     */
    public boolean isValid() {
        return document.getText() != null;
    }

    /**
     * Invoked when the editor is selected.
     * <p/>
     * Update the HTML content if obsolete.
     */
    public void selectNotify() {
        isActive = true;
        if (previewIsObsolete) {
            updateHtmlContent(false);
        }
    }

    private void updateHtmlContent(boolean force) {
        if (updateDelayTimer != null) {
            updateDelayTimer.cancel();
            updateDelayTimer = null;
        }

        if (previewIsObsolete && (isActive || force)) {
            try {
                String html = processor.get().markdownToHtml(document.getText());
                // add class to table rows to compensate for lack of :first-child, :nth-child() so we can have striped tables
                String procHtml = postProcessHtml(html);
                jEditorPane.setText("<div id=\"multimarkdown-preview\">" + procHtml + "</div>");
                previewIsObsolete = false;
            } catch (Exception e) {
                LOGGER.error("Failed processing Markdown document", e);
            }
        }
    }

    protected String postProcessHtml(String html) {
        // scan for <table>, </table>, <tr> and </tr>
        String result = "";
        Pattern p = Pattern.compile("(<table>|<thead>|<tbody>|<tr>)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        int lastPos = 0;
        int rowCount = 0;

        while (m.find()) {
            String found = m.group();
            if (lastPos < m.start(0)) {
                result += html.substring(lastPos, m.start(0));
            }

            if (found.equals("<table>")) {
                rowCount = 0;
                result += found;
            } else if (found.equals("<thead>")) {
//                rowCount = 0;
                result += found;
            } else if (found.equals("<tbody>")) {
//                rowCount = 0;
                result += found;
            } else if (found.equals("<tr>")) {
                rowCount++;
                result += "<tr class=\"" + (rowCount == 1 ? "first-child" : (rowCount & 1) != 0 ? "odd-child" : "even-child") + "\">";
            }

            lastPos = m.end(0);
        }

        if (lastPos < html.length()) {
            result += html.substring(lastPos);
        }

        return result;
    }

    /**
     * Invoked when the editor is deselected.
     * <p/>
     * Does nothing.
     */
    public void deselectNotify() {
        isActive = false;
    }

    /**
     * Add specified listener.
     * <p/>
     * Does nothing.
     *
     * @param listener the listener.
     */
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    /**
     * Remove specified listener.
     * <p/>
     * Does nothing.
     *
     * @param listener the listener.
     */
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    /**
     * Get the background editor highlighter.
     *
     * @return {@code null} as {@link MarkdownPreviewEditor} does not require highlighting.
     */
    @Nullable
    public BackgroundEditorHighlighter getBackgroundHighlighter() {
        return null;
    }

    /**
     * Get the current location.
     *
     * @return {@code null} as {@link MarkdownPreviewEditor} is not navigable.
     */
    @Nullable
    public FileEditorLocation getCurrentLocation() {
        return null;
    }

    /**
     * Get the structure view builder.
     *
     * @return TODO {@code null} as parsing/PSI is not implemented.
     */
    @Nullable
    public StructureViewBuilder getStructureViewBuilder() {
        return null;
    }

    /** Dispose the editor. */
    public void dispose() {
        MarkdownGlobalSettings.getInstance().removeListener(globalSettingsListener);
        Disposer.dispose(this);
    }
}
