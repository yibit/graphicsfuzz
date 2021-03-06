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

package com.graphicsfuzz.generator.tool;

import com.graphicsfuzz.common.ast.TranslationUnit;
import com.graphicsfuzz.common.ast.decl.VariableDeclInfo;
import com.graphicsfuzz.common.ast.decl.VariablesDeclaration;
import com.graphicsfuzz.common.ast.type.BasicType;
import com.graphicsfuzz.common.ast.type.QualifiedType;
import com.graphicsfuzz.common.ast.type.TypeQualifier;
import com.graphicsfuzz.common.glslversion.ShadingLanguageVersion;
import com.graphicsfuzz.common.tool.StatsVisitor;
import com.graphicsfuzz.common.transformreduce.Constants;
import com.graphicsfuzz.common.util.Helper;
import com.graphicsfuzz.common.util.IRandom;
import com.graphicsfuzz.common.util.IdGenerator;
import com.graphicsfuzz.common.util.ParseHelper;
import com.graphicsfuzz.common.util.ParseTimeoutException;
import com.graphicsfuzz.common.util.RandomWrapper;
import com.graphicsfuzz.common.util.ShaderKind;
import com.graphicsfuzz.common.util.StripUnusedFunctions;
import com.graphicsfuzz.common.util.StripUnusedGlobals;
import com.graphicsfuzz.common.util.UniformsInfo;
import com.graphicsfuzz.generator.FloatLiteralReplacer;
import com.graphicsfuzz.generator.transformation.ITransformation;
import com.graphicsfuzz.generator.transformation.controlflow.AddDeadOutputVariableWrites;
import com.graphicsfuzz.generator.transformation.controlflow.AddJumpStmts;
import com.graphicsfuzz.generator.transformation.controlflow.AddLiveOutputVariableWrites;
import com.graphicsfuzz.generator.transformation.controlflow.AddSwitchStmts;
import com.graphicsfuzz.generator.transformation.controlflow.AddWrappingConditionalStmts;
import com.graphicsfuzz.generator.transformation.controlflow.SplitForLoops;
import com.graphicsfuzz.generator.transformation.donation.DonateDeadCode;
import com.graphicsfuzz.generator.transformation.donation.DonateLiveCode;
import com.graphicsfuzz.generator.transformation.mutator.MutateExpressions;
import com.graphicsfuzz.generator.transformation.outliner.OutlineStatements;
import com.graphicsfuzz.generator.transformation.structifier.Structification;
import com.graphicsfuzz.generator.transformation.vectorizer.VectorizeStatements;
import com.graphicsfuzz.generator.util.GenerationParams;
import com.graphicsfuzz.generator.util.TransformationProbabilities;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

public class Generate {

  private static final boolean SHOW_STAGES = false; // Set to true for debugging
  private static int stageCount = 0;

  private static Namespace parse(String[] args) {
    ArgumentParser parser = ArgumentParsers.newArgumentParser("Generate")
          .defaultHelp(true)
          .description("Generate a shader.");

    // Required arguments
    parser.addArgument("reference_prefix")
        .help("Prefix associated with reference shaders and accompanying metadata.")
        .type(String.class);

    parser.addArgument("donors")
          .help("Path of folder of donor shaders.")
          .type(File.class);

    parser.addArgument("glsl_version")
          .help("Version of GLSL to target.")
          .type(String.class);

    parser.addArgument("output_prefix")
          .help("Prefix of target file name, e.g. \"foo\" if fragment shader is to be "
              + "\"foo.frag\".")
          .type(String.class);

    // TODO: add other shader kinds, such as geometry, in due course.

    addGeneratorCommonArguments(parser);

    parser.addArgument("--output_dir")
          .help("Directory for output.")
          .type(File.class)
          .setDefault(new File("."));

    try {
      return parser.parseArgs(args);
    } catch (ArgumentParserException exception) {
      exception.getParser().handleError(exception);
      System.exit(1);
      return null;
    }

  }

  public static void addGeneratorCommonArguments(ArgumentParser parser) {
    parser.addArgument("--seed")
          .help("Seed to initialize random number generator with.")
          .setDefault(new Random().nextInt())
          .type(Integer.class);

    parser.addArgument("--webgl")
          .help("Restrict to WebGL-compatible features.")
          .action(Arguments.storeTrue());

    parser.addArgument("--small")
          .help("Try to generate small shaders.")
          .action(Arguments.storeTrue());

    parser.addArgument("--avoid_long_loops")
          .help("During live code injection, reduce the chances of injecting lengthy loops by "
                + "ensuring that loops do not appear *directly* in the code being injected; they "
                + "may still appear in functions called by the injected code (avoidance of this "
                + "could be added if needed).")
          .action(Arguments.storeTrue());

    parser.addArgument("--disable")
          .help("Disable a given series of transformations.")
          .type(String.class);

    parser.addArgument("--enable_only")
        .help("Disable all but the given series of transformations.")
        .type(String.class);

    parser.addArgument("--aggressively_complicate_control_flow")
          .help("Make control flow very complicated.")
          .action(Arguments.storeTrue());

    parser.addArgument("--multi_pass")
          .help("Apply multiple transformation passes, each with low probablity.")
          .action(Arguments.storeTrue());

    parser.addArgument("--replace_float_literals")
          .help("Replace float literals with uniforms.")
          .action(Arguments.storeTrue());
  }

  public static void generateVariant(GeneratorArguments args) throws IOException,
          ParseTimeoutException {
    final UniformsInfo uniformsInfo = new UniformsInfo(args.getUniforms());
    setInjectionSwitch(uniformsInfo);

    StringBuilder transformationsApplied = new StringBuilder();

    if (args.hasReferenceFragmentShader()) {
      generateShader(args, uniformsInfo, transformationsApplied,
              ShaderKind.FRAGMENT, args.getReferenceFragmentShader());
    }

    if (args.hasReferenceVertexShader()) {
      generateShader(args, uniformsInfo, transformationsApplied,
              ShaderKind.VERTEX, args.getReferenceVertexShader());
    }

    Helper.emitUniformsInfo(uniformsInfo, new PrintStream(
          new FileOutputStream(
                new File(args.getOutputFolder(), args.getOutputPrefix() + ".json"))));

    emitTransformationInfo(transformationsApplied, new PrintStream(
          new FileOutputStream(
                new File(args.getOutputFolder(), args.getOutputPrefix() + ".prob"))));
  }

  private static void generateShader(GeneratorArguments args, UniformsInfo uniformsInfo,
                                     StringBuilder transformationsApplied, ShaderKind shaderKind,
                                     File shaderFile) throws IOException, ParseTimeoutException {
    transformationsApplied.append("======\n" + shaderKind + ":\n");
    final TranslationUnit referenceShader = getRecipientTranslationUnit(shaderFile);

    if (args.getReplaceFloatLiterals()) {
      FloatLiteralReplacer.replace(referenceShader, uniformsInfo, args.getShadingLanguageVersion());
    }
    uniformsInfo.zeroUnsetUniforms(referenceShader);

    final IRandom generator = new RandomWrapper(args.getSeed());

    final GenerationParams generationParams =
            args.getSmall()
                    ? GenerationParams.small(shaderKind)
                    : GenerationParams.normal(shaderKind);

    final TransformationProbabilities probabilities =
            createProbabilities(args, generator);

    transformationsApplied.append(probabilities);

    if (args.getAggressivelyComplicateControlFlow()) {
      transformationsApplied.append(applyControlFlowComplication(args, referenceShader, generator,
              generationParams,
              probabilities));
    } else if (args.getMultiPass()) {
      transformationsApplied.append(applyTransformationsMultiPass(args, referenceShader,
              generator, generationParams,
              probabilities));
    } else {
      transformationsApplied.append(applyTransformationsRandomly(args, referenceShader,
              generator, generationParams,
              probabilities));
    }

    if (args.getSmall()) {
      StripUnusedFunctions.strip(referenceShader);
      StripUnusedGlobals.strip(referenceShader);
    }

    randomiseUnsetUniforms(referenceShader, uniformsInfo, generator.spawnChild());

    Helper.emitShader(args.getShadingLanguageVersion(), shaderKind, referenceShader,
            Helper.readLicenseFile(args.getLicense()),
            new PrintStream(new FileOutputStream(
                    new File(args.getOutputFolder(), args.getOutputPrefix()
                            + shaderKind.getFileExtension()))));
  }

  public static void main(String[] args) throws IOException {

    Namespace ns = parse(args);

    try {

      final EnabledTransformations enabledTransformations
            = getTransformationDisablingFlags(ns);
      final ShadingLanguageVersion shadingLanguageVersion = ns.get("webgl")
          ? ShadingLanguageVersion.webGlFromVersionString(ns.get("glsl_version"))
          : ShadingLanguageVersion.fromVersionString(ns.get("glsl_version"));
      generateVariant(
            new GeneratorArguments(shadingLanguageVersion,
                  ns.get("reference_prefix"),
                  ns.get("seed"),
                  ns.getBoolean("small"),
                  ns.getBoolean("avoid_long_loops"),
                  ns.getBoolean("multi_pass"),
                  ns.getBoolean("aggressively_complicate_control_flow"),
                  ns.getBoolean("replace_float_literals"),
                  ns.get("donors"),
                  ns.get("output_dir"),
                  ns.getString("output_prefix"),
                enabledTransformations
            )
      );

    } catch (Throwable exception) {
      exception.printStackTrace();
      System.exit(1);
    }
  }

  public static EnabledTransformations getTransformationDisablingFlags(Namespace ns) {
    final EnabledTransformations result = new EnabledTransformations();
    final List<Class<? extends ITransformation>> toDisable = new ArrayList<>();
    if (ns.get("disable") != null) {
      if (ns.get("enable_only") != null) {
        throw new RuntimeException("--disable and --enable_only are not compatible");
      }
      toDisable.addAll(EnabledTransformations.namesToList(ns.get("disable")));
    } else if (ns.get("enable_only") != null) {
      toDisable.addAll(EnabledTransformations.allTransformations());
      toDisable.removeAll(EnabledTransformations.namesToList(ns.get("enable_only")));
    }
    toDisable.forEach(result::disable);
    return result;
  }

  private static TransformationProbabilities createProbabilities(
        GeneratorArguments args,
        IRandom generator) {
    if (args.getAggressivelyComplicateControlFlow()) {
      return TransformationProbabilities.closeTo(generator,
            TransformationProbabilities.AGGRESSIVE_CONTROL_FLOW);
    }
    if (args.getMultiPass()) {
      return TransformationProbabilities.randomProbabilitiesMultiPass(generator);
    }
    return TransformationProbabilities.randomProbabilitiesSinglePass(generator);
  }

  private static String applyTransformationsMultiPass(GeneratorArguments args,
        TranslationUnit reference, IRandom generator,
        GenerationParams generationParams, TransformationProbabilities probabilities) {
    List<ITransformation> transformations = populateTransformations(args,
          generationParams, probabilities);
    {
      // Keep roughly half of them
      final List<ITransformation> toKeep = new ArrayList<>();
      while (!transformations.isEmpty()) {
        int index = generator.nextInt(transformations.size());
        if (transformations.size() == 1 && toKeep.isEmpty()) {
          toKeep.add(transformations.remove(index));
        } else {
          ITransformation candidate = transformations.remove(index);
          if (candidate instanceof AddSwitchStmts) {
            // Compilers are so variable in whether they accept switch statements that
            // we generate, so we make this an unusual transformation to apply to avoid
            // tons of compile fail results.
            if (generator.nextInt(8) == 0) {
              toKeep.add(candidate);
            }
          } else if (generator.nextBoolean()) {
            toKeep.add(candidate);
          }
        }
      }
      transformations = toKeep;
    }

    List<ITransformation> done = new ArrayList<>();
    String result = "";
    while (!shaderLargeEnough(reference, generator)) {
      ITransformation transformation = transformations.remove(generator.nextInt(
            transformations.size()));
      result += transformation.getName() + "\n";
      transformation.apply(reference, probabilities, args.getShadingLanguageVersion(),
            generator.spawnChild(),
            generationParams);
      // Keep the size down by stripping unused stuff.
      StripUnusedFunctions.strip(reference);
      StripUnusedGlobals.strip(reference);
      done.add(transformation);
      if (transformations.isEmpty()) {
        transformations = done;
        done = new ArrayList<>();
      }
    }
    return result;
  }

  private static boolean shaderLargeEnough(TranslationUnit tu, IRandom generator) {
    StatsVisitor statsVisitor = new StatsVisitor();
    statsVisitor.visit(tu);

    // WebGL:
    //final int minNodes = 3000;
    //final int maxNodes = 20000;

    final int minNodes = 5000;
    final int maxNodes = 22000;
    final int nodeLimit = generator.nextInt(maxNodes - minNodes) + minNodes;

    return statsVisitor.getNodes() > nodeLimit;

  }

  private static String applyTransformationsRandomly(
        GeneratorArguments args,
        TranslationUnit reference,
        IRandom generator,
        GenerationParams generationParams,
        TransformationProbabilities probabilities)
        throws FileNotFoundException {
    String result = "";

    final List<ITransformation> transformations = populateTransformations(args,
          generationParams, probabilities);

    int numTransformationsApplied = 0;
    while (!transformations.isEmpty()) {
      int index = generator.nextInt(transformations.size());
      ITransformation transformation = transformations.remove(index);

      // We randomly choose whether to apply a transformation, unless this is the last
      // opportunity and we have not applied any transformation yet.
      if ((transformations.isEmpty() && numTransformationsApplied == 0)
            || decideToApplyTransformation(generator, numTransformationsApplied)) {
        showStage(reference, generationParams.getShaderKind(), transformation.getName(),
                args.getShadingLanguageVersion());
        result += transformation.getName() + "\n";
        transformation.apply(reference, probabilities, args.getShadingLanguageVersion(),
              generator.spawnChild(),
              generationParams);
        numTransformationsApplied++;
      }
    }
    return result;
  }

  private static List<ITransformation> populateTransformations(
          GeneratorArguments args,
          GenerationParams generationParams,
          TransformationProbabilities probabilities) {
    List<ITransformation> result = new ArrayList<>();
    final EnabledTransformations flags = args.getEnabledTransformations();
    if (flags.isEnabledDead()) {
      result.add(new DonateDeadCode(probabilities::donateDeadCodeAtStmt, args.getDonorsFolder(),
              generationParams));
    }
    if (flags.isEnabledJump()) {
      result.add(new AddJumpStmts());
    }
    if (flags.isEnabledLive()) {
      result.add(new DonateLiveCode(probabilities::donateLiveCodeAtStmt, args.getDonorsFolder(),
              generationParams, args.getAvoidLongLoops()));
    }
    if (flags.isEnabledMutate()) {
      result.add(new MutateExpressions());
    }
    if (flags.isEnabledOutline()) {
      result.add(new OutlineStatements(new IdGenerator()));
    }
    if (flags.isEnabledSplit()) {
      result.add(new SplitForLoops());
    }
    if (flags.isEnabledStruct()) {
      result.add(new Structification());
    }
    if (flags.isEnabledSwitch()
            && args.getShadingLanguageVersion().supportedSwitchStmt()) {
      result.add(new AddSwitchStmts());
    }
    if (flags.isEnabledVec()) {
      result.add(new VectorizeStatements());
    }
    if (flags.isEnabledWrap()) {
      result.add(new AddWrappingConditionalStmts());
    }
    if (generationParams.getShaderKind() == ShaderKind.FRAGMENT
            && flags.isEnabledDeadFragColorWrites()) {
      result.add(new AddDeadOutputVariableWrites());
    }
    if (flags.isEnabledLiveFragColorWrites()) {
      result.add(new AddLiveOutputVariableWrites());
    }
    if (result.isEmpty()) {
      throw new RuntimeException("At least one transformation must be enabled");
    }
    return result;
  }

  private static boolean decideToApplyTransformation(IRandom generator,
        int numTransformationsAppliedSoFar) {
    return generator.nextFloat() < 0.5 * Math.pow(0.9, numTransformationsAppliedSoFar);
  }

  private static String applyControlFlowComplication(GeneratorArguments args,
        TranslationUnit reference, IRandom generator, GenerationParams generationParams,
        TransformationProbabilities probabilities) {
    String result = "";
    List<ITransformation> transformations = new ArrayList<>();
    transformations.add(new AddJumpStmts());
    transformations.add(new OutlineStatements(new IdGenerator()));
    transformations.add(new AddWrappingConditionalStmts());
    transformations.add(new AddSwitchStmts());
    transformations.add(new AddDeadOutputVariableWrites());
    transformations.add(new AddLiveOutputVariableWrites());

    final int minIterations = 3;
    final int numIterations = minIterations + generator.nextInt(5);
    for (int i = 0; i < numIterations; i++) {
      ITransformation transformation = transformations
            .get(generator.nextInt(transformations.size()));
      transformation
            .apply(reference, probabilities, args.getShadingLanguageVersion(),
                generator, generationParams);
      result += transformation.getName() + "\n";
    }
    return result;
  }

  private static void emitTransformationInfo(StringBuilder transformationsApplied,
                                             PrintStream stream) {
    stream.println(transformationsApplied.toString());
    stream.close();
  }

  public static void randomiseUnsetUniforms(TranslationUnit tu, UniformsInfo uniformsInfo,
        IRandom generator) {
    final Supplier<Float> floatSupplier = () -> generator.nextFloat();
    final Supplier<Integer> intSupplier = () -> generator.nextInt(1 << 15);
    final Supplier<Integer> uintSupplier = () -> generator.nextInt(1 << 15);
    final Supplier<Integer> boolSupplier = () -> generator.nextInt(2);
    uniformsInfo.setUniforms(tu, floatSupplier, intSupplier, uintSupplier, boolSupplier);
  }

  public static TranslationUnit getRecipientTranslationUnit(File originalFile)
        throws IOException, ParseTimeoutException {
    TranslationUnit original = ParseHelper.parse(originalFile, false);
    addInjectionSwitchIfNotPresent(original);
    return original;
  }


  private static void showStage(TranslationUnit shader, ShaderKind shaderKind, String stageName,
        ShadingLanguageVersion shadingLanguageVersion) throws FileNotFoundException {
    if (SHOW_STAGES) {
      Helper.emitShader(shadingLanguageVersion, shaderKind, shader, new PrintStream(
            new FileOutputStream("__stage_" + stageCount + "_" + stageName + ".frag")));
      stageCount++;
    }
  }

  public static void addInjectionSwitchIfNotPresent(TranslationUnit tu) {
    if (alreadyDeclaresInjectionSwitch(tu)) {
      return;
    }

    List<TypeQualifier> qualifiers = new ArrayList<>();
    qualifiers.add(TypeQualifier.UNIFORM);
    tu.addDeclaration(new VariablesDeclaration(new QualifiedType(BasicType.VEC2, qualifiers),
          new VariableDeclInfo(Constants.INJECTION_SWITCH, null, null)));
  }

  private static boolean alreadyDeclaresInjectionSwitch(TranslationUnit tu) {
    return tu.getGlobalVarDeclInfos()
          .stream()
          .map(item -> item.getName())
          .collect(Collectors.toList())
          .contains(Constants.INJECTION_SWITCH);
  }
  
  public static void setInjectionSwitch(UniformsInfo uniformsInfo) {
    uniformsInfo.addUniform(Constants.INJECTION_SWITCH, BasicType.VEC2, Optional.empty(),
          Arrays.asList(new Float(0.0), new Float(1.0)));
  }

}
