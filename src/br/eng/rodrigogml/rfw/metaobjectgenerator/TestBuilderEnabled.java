package br.eng.rodrigogml.rfw.metaobjectgenerator;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;

import br.eng.rodrigogml.rfw.metaobjectgenerator.handlers.AddBuilder;

public class TestBuilderEnabled extends PropertyTester {

  private static final String IS_ENABLED = "isEnabled";

  @Override
  public boolean test(final Object receiver, final String property, final Object[] args, final Object expectedValue) {

    if (IS_ENABLED.equals(property)) {
      final IProject project = Platform.getAdapterManager().getAdapter(receiver, IProject.class);

      if (project != null) return AddBuilder.hasBuilder(project);
    }

    return false;
  }
}