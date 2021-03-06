/*
 * Copyright 2018 The GraphicsFuzz Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.graphicsfuzz.tester;

import static org.junit.Assert.assertEquals;

import com.graphicsfuzz.common.ast.TranslationUnit;
import com.graphicsfuzz.common.ast.expr.BinOp;
import com.graphicsfuzz.common.ast.expr.BinaryExpr;
import com.graphicsfuzz.common.ast.expr.FloatConstantExpr;
import com.graphicsfuzz.common.ast.expr.MemberLookupExpr;
import com.graphicsfuzz.common.ast.expr.VariableIdentifierExpr;
import com.graphicsfuzz.common.glslversion.ShadingLanguageVersion;
import com.graphicsfuzz.common.tool.PrettyPrinterVisitor;
import com.graphicsfuzz.common.transformreduce.Constants;
import com.graphicsfuzz.common.util.EmitShaderHelper;
import com.graphicsfuzz.util.ExecHelper.RedirectType;
import com.graphicsfuzz.util.ExecResult;
import com.graphicsfuzz.common.util.Helper;
import com.graphicsfuzz.common.util.IRandom;
import com.graphicsfuzz.common.util.IdGenerator;
import com.graphicsfuzz.common.util.ParseHelper;
import com.graphicsfuzz.common.util.ParseTimeoutException;
import com.graphicsfuzz.common.util.RandomWrapper;
import com.graphicsfuzz.common.util.ShaderKind;
import com.graphicsfuzz.util.ToolHelper;
import com.graphicsfuzz.common.util.UniformsInfo;
import com.graphicsfuzz.generator.tool.Generate;
import com.graphicsfuzz.generator.transformation.ITransformation;
import com.graphicsfuzz.generator.transformation.controlflow.AddDeadOutputVariableWrites;
import com.graphicsfuzz.generator.transformation.controlflow.AddJumpStmts;
import com.graphicsfuzz.generator.transformation.controlflow.AddLiveOutputVariableWrites;
import com.graphicsfuzz.generator.transformation.controlflow.SplitForLoops;
import com.graphicsfuzz.generator.transformation.donation.DonateDeadCode;
import com.graphicsfuzz.generator.transformation.donation.DonateLiveCode;
import com.graphicsfuzz.generator.transformation.mutator.MutateExpressions;
import com.graphicsfuzz.generator.transformation.outliner.OutlineStatements;
import com.graphicsfuzz.generator.util.GenerationParams;
import com.graphicsfuzz.generator.util.TransformationProbabilities;
import com.graphicsfuzz.reducer.CheckAstFeatureVisitor;
import com.graphicsfuzz.reducer.CheckAstFeaturesFileJudge;
import com.graphicsfuzz.reducer.IFileJudge;
import com.graphicsfuzz.reducer.IReductionStateFileWriter;
import com.graphicsfuzz.reducer.ReductionDriver;
import com.graphicsfuzz.reducer.glslreducers.GlslReductionState;
import com.graphicsfuzz.reducer.glslreducers.GlslReductionStateFileWriter;
import com.graphicsfuzz.reducer.reductionopportunities.IReductionOpportunity;
import com.graphicsfuzz.reducer.reductionopportunities.ReductionOpportunities;
import com.graphicsfuzz.reducer.reductionopportunities.ReductionOpportunityContext;
import com.graphicsfuzz.reducer.tool.RandomFileJudge;
import com.graphicsfuzz.reducer.tool.Reduce;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.bytedeco.javacpp.opencv_core.Mat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReducerUnitTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testReductionSteps() throws Exception {
    List<ITransformationSupplier> transformations = new ArrayList<>();
    int seed = 0;
    for (File originalShader : Util.getReferences()) {
      testGenerateAndReduce(originalShader, transformations, new RandomWrapper(seed));
      seed++;
    }
  }

  private void testGenerateAndReduce(File originalShader, List<ITransformationSupplier> transformations,
      RandomWrapper generator) throws Exception {
    final ShadingLanguageVersion shadingLanguageVersion = ShadingLanguageVersion.ESSL_100;
    final Mat referenceImage = Util.renderShaderIfNeeded(shadingLanguageVersion, originalShader, temporaryFolder, false);
    final UniformsInfo uniformsInfo = new UniformsInfo(
          new File(Helper.jsonFilenameForShader(originalShader.getAbsolutePath())));
    final TranslationUnit tu = generateSizeLimitedShader(originalShader, transformations, generator,
        shadingLanguageVersion);
    Generate.addInjectionSwitchIfNotPresent(tu);
    Generate.setInjectionSwitch(uniformsInfo);
    Generate.randomiseUnsetUniforms(tu, uniformsInfo, generator);

    final IdGenerator idGenerator = new IdGenerator();

    for (int step = 0; step < 10; step++) {
      List<IReductionOpportunity> ops = ReductionOpportunities.getReductionOpportunities(tu,
            new ReductionOpportunityContext(false, shadingLanguageVersion, generator, idGenerator));
      if (ops.isEmpty()) {
        break;
      }
      System.err.println("Step: " + step + "; ops: " + ops.size());
      ops.get(generator.nextInt(ops.size())).applyReduction();

      final Mat variantImage = Util.validateAndGetImage(tu, Optional.of(uniformsInfo), originalShader.getName() + "_reduced_" + step + ".frag",
          shadingLanguageVersion, ShaderKind.FRAGMENT, temporaryFolder);
      Util.assertImagesEquals(referenceImage, variantImage);
    }

  }

  private TranslationUnit generateSizeLimitedShader(File originalShader,
      List<ITransformationSupplier> transformations, IRandom generator,
      ShadingLanguageVersion shadingLanguageVersion) throws IOException, ParseTimeoutException {
    while (true) {
      List<ITransformationSupplier> transformationsCopy = new ArrayList<>();
      transformationsCopy.addAll(transformations);
      final TranslationUnit tu = ParseHelper.parse(originalShader, false);
      for (int i = 0; i < 4; i++) {
        getTransformation(transformationsCopy, generator).apply(
            tu, TransformationProbabilities.DEFAULT_PROBABILITIES, shadingLanguageVersion,
            generator, GenerationParams.normal(ShaderKind.FRAGMENT));
      }
      File tempFile = temporaryFolder.newFile();
      Helper.emitShader(shadingLanguageVersion, ShaderKind.FRAGMENT, tu, new PrintStream(
          new FileOutputStream(tempFile)));
      final int maxBytes = 100000;
      if (tempFile.length() <= maxBytes) {
        return tu;
      }
    }
  }

  private ITransformation getTransformation(List<ITransformationSupplier> transformations,
      IRandom generator) throws IOException {
    if (transformations.isEmpty()) {
      transformations.addAll(getTransformations());
    }
    return transformations.remove(generator.nextInt(transformations.size())).get();
  }

  private List<ITransformationSupplier> getTransformations() {
    List<ITransformationSupplier> result = new ArrayList<>();
    result.add(() -> new AddJumpStmts());
    result.add(() -> new MutateExpressions());
    result.add(() -> new OutlineStatements(new IdGenerator()));
    result.add(() -> new SplitForLoops());
    result.add(() -> new DonateDeadCode(
            TransformationProbabilities.DEFAULT_PROBABILITIES::donateDeadCodeAtStmt,
            Util.createDonorsFolder(temporaryFolder),
            GenerationParams.normal(ShaderKind.FRAGMENT)));
    result.add(() -> new DonateLiveCode(
            TransformationProbabilities.likelyDonateLiveCode()::donateLiveCodeAtStmt,
            Util.createDonorsFolder(temporaryFolder),
            GenerationParams.normal(ShaderKind.FRAGMENT),
            true));
    result.add(() -> new AddDeadOutputVariableWrites());
    result.add(() -> new AddLiveOutputVariableWrites());
    return result;
  }

  public void reduceRepeatedly(String shader, int numIterations,
        int threshold,
        boolean throwExceptionOnInvalid) throws Exception {

    final File shaderFile = Paths.get(TestShadersDirectory.getTestShadersDirectory(), "reducerregressions", shader).toFile();
    final ShadingLanguageVersion shadingLanguageVersion = ShadingLanguageVersion.getGlslVersionFromShader(shaderFile);
    final TranslationUnit tu = Helper.parse(shaderFile, true);

    IRandom generator = new RandomWrapper(4);

    for (int i = 0; i < numIterations; i++) {

      GlslReductionState initialState = new GlslReductionState(
          Optional.empty(),
          Optional.of(tu),
          new UniformsInfo(
              new File(Helper.jsonFilenameForShader(shaderFile.getAbsolutePath()))));
      IReductionStateFileWriter fileWriter = new GlslReductionStateFileWriter(
          shadingLanguageVersion);

      new ReductionDriver(new ReductionOpportunityContext(false,
          shadingLanguageVersion, generator,
            new IdGenerator()), false, initialState)
            .doReduction(FilenameUtils.removeExtension(shaderFile.getAbsolutePath()), 0, fileWriter,
                  new RandomFileJudge(generator, threshold, throwExceptionOnInvalid),
                  temporaryFolder.newFolder(),
                  100);
    }

  }

  @Test
  public void reducerRegression1() throws Exception  {
    reduceRepeatedly("shader1.frag", 10, 10, true);
  }

  @Test
  public void reducerRegression2() throws Exception {
    reduceRepeatedly("shader2.frag", 7, 10, false);
  }

  @Test
  public void reducerRegression3() throws Exception {
    reduceRepeatedly("shader3.frag", 10, 3, true);
  }

  @Test
  public void reducerRegression4() throws Exception {
    reduceRepeatedly("shader3.frag", 10, 10, true);
  }

  @Test
  public void testCrunchThroughLiveCode() throws Exception {
    final IFileJudge involvesSpecificBinaryOperator =
          new CheckAstFeaturesFileJudge(Arrays.asList(() -> new CheckAstFeatureVisitor() {
              @Override
              public void visitBinaryExpr(BinaryExpr binaryExpr) {
                super.visitBinaryExpr(binaryExpr);
                if (binaryExpr.getOp() == BinOp.MUL
                      && binaryExpr.getLhs() instanceof VariableIdentifierExpr
                      && ((VariableIdentifierExpr) binaryExpr.getLhs()).getName()
                      .equals("GLF_live3lifetimeFrac")
                      && binaryExpr.getRhs() instanceof VariableIdentifierExpr
                      && ((VariableIdentifierExpr) binaryExpr.getRhs()).getName()
                      .equals("GLF_live3initialDistance")) {
                  trigger();
                }
              }
            }), ShaderKind.FRAGMENT);

    final File shaderFile = Paths.get(TestShadersDirectory.getTestShadersDirectory(),
        "reducerregressions", "misc1.frag").toFile();
    final String outputFilesPrefix = runReductionOnShader(shaderFile, involvesSpecificBinaryOperator);
    PrettyPrinterVisitor.prettyPrintAsString(Helper.parse(new File(outputFilesPrefix + ".frag"), true));
    // TODO: assert something about the result.
  }

  @Test
  public void testIntricateReduction() throws Exception {

    final Supplier<CheckAstFeatureVisitor> involvesFloatLiteral1 = () -> new CheckAstFeatureVisitor() {
      @Override
      public void visitFloatConstantExpr(FloatConstantExpr floatConstantExpr) {
        super.visitFloatConstantExpr(floatConstantExpr);
        if (floatConstantExpr.getValue().equals("7066.7300")) {
          trigger();
        }
      }
    };

    final Supplier<CheckAstFeatureVisitor> involvesFloatLiteral2 = () -> new CheckAstFeatureVisitor() {
      @Override
      public void visitFloatConstantExpr(FloatConstantExpr floatConstantExpr) {
        super.visitFloatConstantExpr(floatConstantExpr);
        if (floatConstantExpr.getValue().equals("1486.9927")) {
          trigger();
        }
      }
    };

    final Supplier<CheckAstFeatureVisitor> involvesVariable1 = () -> new CheckAstFeatureVisitor() {
      @Override
      public void visitVariableIdentifierExpr(VariableIdentifierExpr variableIdentifierExpr) {
        super.visitVariableIdentifierExpr(variableIdentifierExpr);
        if (variableIdentifierExpr.getName().equals("GLF_live2pixel")) {
          trigger();
        }
      }
    };

    final Supplier<CheckAstFeatureVisitor> involvesVariable2 = () -> new CheckAstFeatureVisitor() {
      @Override
      public void visitVariableIdentifierExpr(VariableIdentifierExpr variableIdentifierExpr) {
        super.visitVariableIdentifierExpr(variableIdentifierExpr);
        if (variableIdentifierExpr.getName().equals("GLF_live2color")) {
          trigger();
        }
      }
    };

    final Supplier<CheckAstFeatureVisitor> involvesVariable3 = () -> new CheckAstFeatureVisitor() {
      @Override
      public void visitVariableIdentifierExpr(VariableIdentifierExpr variableIdentifierExpr) {
        super.visitVariableIdentifierExpr(variableIdentifierExpr);
        if (variableIdentifierExpr.getName().equals("GLF_live0INNER_ITERS")) {
          trigger();
        }
      }
    };

    final Supplier<CheckAstFeatureVisitor> involvesSpecialAssignment = () -> new CheckAstFeatureVisitor() {
      @Override
      public void visitBinaryExpr(BinaryExpr binaryExpr) {
        super.visitBinaryExpr(binaryExpr);
        if (binaryExpr.getOp() != BinOp.ASSIGN) {
          return;
        }
        if (!(binaryExpr.getLhs() instanceof MemberLookupExpr)) {
          return;
        }
        if (!(((MemberLookupExpr) binaryExpr.getLhs()).getStructure() instanceof VariableIdentifierExpr)) {
          return;
        }
        if (!((VariableIdentifierExpr)((MemberLookupExpr) binaryExpr.getLhs()).getStructure()).getName()
              .equals("GLF_merged2_0_1_10_1_1_11GLF_live0tGLF_live0v2")) {
          return;
        }
        if (!((MemberLookupExpr) binaryExpr.getLhs()).getMember().equals("y")) {
          return;
        }
        if (!(binaryExpr.getRhs() instanceof VariableIdentifierExpr)) {
          return;
        }
        if (!(((VariableIdentifierExpr) binaryExpr.getRhs()).getName().equals("GLF_live0v2"))) {
          return;
        }
        trigger();
      }
    };

    IFileJudge judge = new CheckAstFeaturesFileJudge(Arrays.asList(
          involvesFloatLiteral1, involvesFloatLiteral2, involvesVariable1, involvesVariable2, involvesVariable3, involvesSpecialAssignment),
          ShaderKind.FRAGMENT);
    final File shaderFile = Paths.get(TestShadersDirectory.getTestShadersDirectory(),
        "reducerregressions", "intricate.frag").toFile();
    final String outputFilesPrefix = runReductionOnShader(shaderFile, judge);
    PrettyPrinterVisitor.prettyPrintAsString(Helper.parse(new File(outputFilesPrefix + ".frag"), true));
  }

  private String runReductionOnShader(File shaderFile, IFileJudge fileJudge)
        throws IOException, ParseTimeoutException {
    final ShadingLanguageVersion version = ShadingLanguageVersion.getGlslVersionFromShader(shaderFile);
    final IRandom generator = new RandomWrapper(0);
    final TranslationUnit tu = Helper.parse(shaderFile, true);
    final GlslReductionState state = new GlslReductionState(
        Optional.empty(),
        Optional.of(tu),
        new UniformsInfo(new File(Helper.jsonFilenameForShader(shaderFile.getAbsolutePath()))));
    return new ReductionDriver(new ReductionOpportunityContext(false, version, generator, new IdGenerator()), false, state)
        .doReduction(FilenameUtils.removeExtension(shaderFile.getAbsolutePath()), 0,
          new GlslReductionStateFileWriter(version),
          fileJudge, temporaryFolder.getRoot(), -1);
  }

  @Test
  public void testBasicReduction() throws Exception {

    final String program =
          EmitShaderHelper.getDefinesString(
                ShadingLanguageVersion.ESSL_100,
                ShaderKind.FRAGMENT,
                Helper::glfMacros,
                Optional.empty()) + "\n"
          + "void main() {"
          + "  float x = 0.0;"
          + "  float y = 0.0;"
          + "  x = x + 0.1 + y + y + y + y + y;"
          + "  x = x + 0.1 + y + y + y + y + y;"
          + "  x = x + 0.1 + y + y + y + y + y;"
          + "  x = x + 0.1 + y + y + y + y + y;"
          + "  x = x + 0.1 + y + y + y + y + y;"
          + "  x = x + 0.1 + y + y + y + y + y;"
          + "  x = x + 0.1 + y + y + y + y + y;"
          + "  x = x + 0.1 + y + y + y + y + y;"
          + "  x = x + 0.1 + y + y + y + y + y;"
          + "  x = x + 0.1 + y + y + y + y + y;"
          + "  gl_FragColor = vec4(x, 0.0, 0.0, 1.0);"
          + "}";
    final File reference = temporaryFolder.newFile("reference.frag");
    final File referenceJson = temporaryFolder.newFile("reference.json");
    final File referenceImage = temporaryFolder.newFile("reference.png");
    FileUtils.writeStringToFile(reference, program, StandardCharsets.UTF_8);
    FileUtils.writeStringToFile(referenceJson, "{ }", StandardCharsets.UTF_8);

    final ExecResult referenceResult = ToolHelper.runSwiftshaderOnShader(RedirectType.TO_LOG,
          reference, referenceImage, false);
    assertEquals(0, referenceResult.res);

    final File output = temporaryFolder.newFolder();

    int numSteps = 20;

    Reduce.main(new String[] {
          FilenameUtils.removeExtension(reference.getAbsolutePath()),
          "--swiftshader",
          "IDENTICAL",
          "--reduce_everywhere",
          "--reference_image",
          referenceImage.getAbsolutePath(),
          "--max_steps",
          String.valueOf(numSteps),
          "--seed",
          "0",
          "--output",
          output.getAbsolutePath()
    });

    while (new File(output, Constants.REDUCTION_INCOMPLETE).exists()) {
      numSteps += 5;
      Reduce.main(new String[] {
            FilenameUtils.removeExtension(reference.getAbsolutePath()),
            "--swiftshader",
            "IDENTICAL",
            "--reduce_everywhere",
            "--reference_image",
            referenceImage.getAbsolutePath(),
            "--max_steps",
            String.valueOf(numSteps),
            "--seed",
            "0",
            "--output",
            output.getAbsolutePath(),
            "--continue_previous_reduction"
      });
    }

    final File[] finalResults = output.listFiles((dir, file)
          -> file.contains("final") && file.endsWith(".frag"));
    assertEquals(1, finalResults.length);
    assertEquals(
          PrettyPrinterVisitor.prettyPrintAsString(Helper.parse("void main() { gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0); }", false)),
          PrettyPrinterVisitor.prettyPrintAsString(ParseHelper.parse(finalResults[0], true)));
  }

  @Test(expected = FileNotFoundException.class)
  public void testConditionForContinuingReduction() throws Exception {
    final String program =
          EmitShaderHelper.getDefinesString(
                ShadingLanguageVersion.ESSL_100,
                ShaderKind.FRAGMENT,
                Helper::glfMacros,
                Optional.empty()) + "\n"
                + "void main() {"
                + "}";
    final File shader = temporaryFolder.newFile("reference.frag");
    final File json = temporaryFolder.newFile("reference.json");
    FileUtils.writeStringToFile(shader, program, StandardCharsets.UTF_8);
    FileUtils.writeStringToFile(json, "{ }", StandardCharsets.UTF_8);
    final File output = temporaryFolder.newFolder();
    // This should throw a FileNotFoundException, because REDUCTION_INCOMPLETE
    // will not be present.
    Reduce.mainHelper(new String[] { "--swiftshader", "--continue_previous_reduction",
          shader.getAbsolutePath(), "--output",
          output.getAbsolutePath(), "NO_IMAGE" }, null);
  }

  @Test
  public void checkReductionIsFinite() throws Exception {
    final String program =
          EmitShaderHelper.getDefinesString(
                ShadingLanguageVersion.ESSL_100,
                ShaderKind.FRAGMENT,
                Helper::glfMacros,
                Optional.empty()) + "\n"
                + "void main() {"
                + "  gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);"
                + "}";
    final File reference = temporaryFolder.newFile("reference.frag");
    final File referenceJson = temporaryFolder.newFile("reference.json");
    final File referenceImage = temporaryFolder.newFile("reference.png");
    FileUtils.writeStringToFile(reference, program, StandardCharsets.UTF_8);
    FileUtils.writeStringToFile(referenceJson, "{ }", StandardCharsets.UTF_8);

    final ExecResult referenceResult = ToolHelper.runSwiftshaderOnShader(RedirectType.TO_LOG,
          reference, referenceImage, false);
    assertEquals(0, referenceResult.res);
    final File output = temporaryFolder.newFolder();
    Reduce.main(new String[] {
          FilenameUtils.removeExtension(reference.getAbsolutePath()),
          "--swiftshader",
          "IDENTICAL",
          "--reduce_everywhere",
          "--reference_image",
          referenceImage.getAbsolutePath(),
          "--max_steps",
          "-1",
          "--seed",
          "0",
          "--output",
          output.getAbsolutePath() });
  }

}
