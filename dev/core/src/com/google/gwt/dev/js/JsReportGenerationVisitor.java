/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.js;

import com.google.gwt.core.ext.soyc.Range;
import com.google.gwt.dev.jjs.JsSourceMap;
import com.google.gwt.dev.jjs.SourceInfo;
import com.google.gwt.dev.jjs.impl.JavaToJavaScriptMap;
import com.google.gwt.dev.js.ast.JsBlock;
import com.google.gwt.dev.js.ast.JsDoWhile;
import com.google.gwt.dev.js.ast.JsExpression;
import com.google.gwt.dev.js.ast.JsName;
import com.google.gwt.dev.js.ast.JsNameRef;
import com.google.gwt.dev.js.ast.JsNode;
import com.google.gwt.dev.js.ast.JsStatement;
import com.google.gwt.dev.js.ast.JsVisitable;
import com.google.gwt.dev.util.TextOutput;
import com.google.gwt.thirdparty.guava.common.annotations.VisibleForTesting;
import com.google.gwt.thirdparty.guava.common.collect.Lists;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * A variation on the standard source generation visitor that records the
 * locations of SourceInfo objects in the output.
 */
public class JsReportGenerationVisitor extends
    JsSourceGenerationVisitorWithSizeBreakdown {
  private final LinkedHashMap<Range, SourceInfo> sourceInfoMap =
      new LinkedHashMap<Range, SourceInfo>();
  private final TextOutput out;
  private final boolean needSourcemapNames;

  /**
   * The key of the most recently added Javascript range for a descendant
   * of the current node.
   */
  private Range previousChildKey = null;

  /**
   * The ancestor nodes whose Range and SourceInfo will be added to the sourcemap.
   */
  private List<JsNode> parentStack = Lists.newArrayList();

  public JsReportGenerationVisitor(TextOutput out, JavaToJavaScriptMap map,
      boolean needSourcemapNames) {
    super(out, map);
    this.out = out;
    this.needSourcemapNames = needSourcemapNames;
  }

  @Override
  protected <T extends JsVisitable> T generateAndBill(T node, JsName nameToBillTo) {
    previousChildKey = null; // It's not our child because we haven't visited our children yet.

    if (!(node instanceof JsNode)) {
      return super.generateAndBill(node, nameToBillTo);
    }

    boolean willReportRange = false;
    if (node instanceof JsBlock) {
      willReportRange = false; // Only report the statements within the block
    } else if (parentStack.isEmpty()) {
      willReportRange = true;
    } else if (node instanceof JsStatement) {
      willReportRange = true;
    } else if ((node instanceof JsNameRef) && needSourcemapNames) {
      willReportRange = true;
    } else {
      JsNode parent = parentStack.get(parentStack.size() - 1);
      if ((node instanceof JsExpression) &&
          (parent instanceof JsDoWhile)) {
        // Always instrument the expression because it comes at the end.
        // (So we can stop there in a loop.)
        willReportRange = true;
      } else {
        // Instrument the expression if it was inlined in Java.
        SourceInfo info = ((JsNode) node).getSourceInfo();
        if (!surroundsInJavaSource(parent.getSourceInfo(), info)) {
          willReportRange = true;
        }
      }
    }

    // Remember the position before generating the JavaScript.
    int beforePosition = out.getPosition();
    int beforeLine = out.getLine();
    int beforeColumn = out.getColumn();

    if (willReportRange) {
      parentStack.add((JsNode) node);
    }

    // Write some JavaScript (changing the position).
    T toReturn = super.generateAndBill(node, nameToBillTo);

    if (willReportRange) {
      parentStack.remove(parentStack.size() - 1);
    }

    if (out.getPosition() > beforePosition && willReportRange) {

      SourceInfo info = ((JsNode) node).getSourceInfo();
      Range javaScriptRange = new Range(beforePosition, out.getPosition(),
        beforeLine, beforeColumn, out.getLine(), out.getColumn());
      sourceInfoMap.put(javaScriptRange, info);

      previousChildKey = javaScriptRange; // Report this child to its parent.
    }

    return toReturn;
  }

  /**
   * Returns true if the given parent's range as Java source code surrounds
   * the child.
   */
  @VisibleForTesting
  boolean surroundsInJavaSource(SourceInfo parent, SourceInfo child) {
    if (!hasValidJavaRange(parent) || !hasValidJavaRange(child)) {
      return false;
    }
    return parent.getStartPos() <= child.getStartPos() && child.getEndPos() <= parent.getEndPos()
        && child.getFileName().equals(parent.getFileName());
  }

  private boolean hasValidJavaRange(SourceInfo info) {
    return info != null && info.getStartPos() >= 0 && info.getEndPos() >= info.getStartPos();
  }

  @Override
  protected void billChildToHere() {
    if (previousChildKey != null && previousChildKey.getEnd() < out.getPosition()) {
      SourceInfo value = sourceInfoMap.get(previousChildKey);
      Range newKey = previousChildKey.withNewEnd(out.getPosition(), out.getLine(),
          out.getColumn());
      sourceInfoMap.remove(previousChildKey);
      sourceInfoMap.put(newKey, value);
      previousChildKey = newKey;
    }
  }

  @Override
  public JsSourceMap getSourceInfoMap() {
    return new JsSourceMap(sourceInfoMap, out.getPosition(), out.getLine());
  }
}
