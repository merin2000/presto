/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.sql.analyzer;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import io.airlift.slice.SliceUtf8;
import io.prestosql.Session;
import io.prestosql.SystemSessionProperties;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.OperatorNotFoundException;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.metadata.Signature;
import io.prestosql.operator.scalar.FormatFunction;
import io.prestosql.security.AccessControl;
import io.prestosql.security.DenyAllAccessControl;
import io.prestosql.spi.ErrorCodeSupplier;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.type.CharType;
import io.prestosql.spi.type.DecimalParseResult;
import io.prestosql.spi.type.Decimals;
import io.prestosql.spi.type.RowType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeNotFoundException;
import io.prestosql.spi.type.TypeSignatureParameter;
import io.prestosql.spi.type.VarcharType;
import io.prestosql.sql.parser.SqlParser;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.tree.ArithmeticBinaryExpression;
import io.prestosql.sql.tree.ArithmeticUnaryExpression;
import io.prestosql.sql.tree.ArrayConstructor;
import io.prestosql.sql.tree.AtTimeZone;
import io.prestosql.sql.tree.BetweenPredicate;
import io.prestosql.sql.tree.BinaryLiteral;
import io.prestosql.sql.tree.BindExpression;
import io.prestosql.sql.tree.BooleanLiteral;
import io.prestosql.sql.tree.Cast;
import io.prestosql.sql.tree.CharLiteral;
import io.prestosql.sql.tree.CoalesceExpression;
import io.prestosql.sql.tree.ComparisonExpression;
import io.prestosql.sql.tree.CurrentPath;
import io.prestosql.sql.tree.CurrentTime;
import io.prestosql.sql.tree.CurrentUser;
import io.prestosql.sql.tree.DecimalLiteral;
import io.prestosql.sql.tree.DereferenceExpression;
import io.prestosql.sql.tree.DoubleLiteral;
import io.prestosql.sql.tree.ExistsPredicate;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.Extract;
import io.prestosql.sql.tree.FieldReference;
import io.prestosql.sql.tree.Format;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.GenericLiteral;
import io.prestosql.sql.tree.GroupingOperation;
import io.prestosql.sql.tree.Identifier;
import io.prestosql.sql.tree.IfExpression;
import io.prestosql.sql.tree.InListExpression;
import io.prestosql.sql.tree.InPredicate;
import io.prestosql.sql.tree.IntervalLiteral;
import io.prestosql.sql.tree.IsNotNullPredicate;
import io.prestosql.sql.tree.IsNullPredicate;
import io.prestosql.sql.tree.LambdaArgumentDeclaration;
import io.prestosql.sql.tree.LambdaExpression;
import io.prestosql.sql.tree.LikePredicate;
import io.prestosql.sql.tree.LogicalBinaryExpression;
import io.prestosql.sql.tree.LongLiteral;
import io.prestosql.sql.tree.Node;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.NotExpression;
import io.prestosql.sql.tree.NullIfExpression;
import io.prestosql.sql.tree.NullLiteral;
import io.prestosql.sql.tree.Parameter;
import io.prestosql.sql.tree.QualifiedName;
import io.prestosql.sql.tree.QuantifiedComparisonExpression;
import io.prestosql.sql.tree.Row;
import io.prestosql.sql.tree.SearchedCaseExpression;
import io.prestosql.sql.tree.SimpleCaseExpression;
import io.prestosql.sql.tree.SortItem;
import io.prestosql.sql.tree.StackableAstVisitor;
import io.prestosql.sql.tree.StringLiteral;
import io.prestosql.sql.tree.SubqueryExpression;
import io.prestosql.sql.tree.SubscriptExpression;
import io.prestosql.sql.tree.SymbolReference;
import io.prestosql.sql.tree.TimeLiteral;
import io.prestosql.sql.tree.TimestampLiteral;
import io.prestosql.sql.tree.TryExpression;
import io.prestosql.sql.tree.WhenClause;
import io.prestosql.sql.tree.WindowFrame;
import io.prestosql.type.FunctionType;
import io.prestosql.type.TypeCoercion;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.prestosql.spi.StandardErrorCode.EXPRESSION_NOT_CONSTANT;
import static io.prestosql.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.prestosql.spi.StandardErrorCode.INVALID_LITERAL;
import static io.prestosql.spi.StandardErrorCode.INVALID_PARAMETER_USAGE;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.StandardErrorCode.TOO_MANY_ARGUMENTS;
import static io.prestosql.spi.StandardErrorCode.TYPE_MISMATCH;
import static io.prestosql.spi.StandardErrorCode.TYPE_NOT_FOUND;
import static io.prestosql.spi.function.OperatorType.SUBSCRIPT;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.RealType.REAL;
import static io.prestosql.spi.type.SmallintType.SMALLINT;
import static io.prestosql.spi.type.TimeType.TIME;
import static io.prestosql.spi.type.TimeWithTimeZoneType.TIME_WITH_TIME_ZONE;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.TimestampWithTimeZoneType.TIMESTAMP_WITH_TIME_ZONE;
import static io.prestosql.spi.type.TinyintType.TINYINT;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.spi.type.Varchars.isVarcharType;
import static io.prestosql.sql.NodeUtils.getSortItemsFromOrderBy;
import static io.prestosql.sql.analyzer.Analyzer.verifyNoAggregateWindowOrGroupingFunctions;
import static io.prestosql.sql.analyzer.ExpressionTreeUtils.extractLocation;
import static io.prestosql.sql.analyzer.SemanticExceptions.missingAttributeException;
import static io.prestosql.sql.analyzer.SemanticExceptions.semanticException;
import static io.prestosql.sql.tree.ArrayConstructor.ARRAY_CONSTRUCTOR;
import static io.prestosql.sql.tree.Extract.Field.TIMEZONE_HOUR;
import static io.prestosql.sql.tree.Extract.Field.TIMEZONE_MINUTE;
import static io.prestosql.type.ArrayParametricType.ARRAY;
import static io.prestosql.type.IntervalDayTimeType.INTERVAL_DAY_TIME;
import static io.prestosql.type.IntervalYearMonthType.INTERVAL_YEAR_MONTH;
import static io.prestosql.type.JsonType.JSON;
import static io.prestosql.type.UnknownType.UNKNOWN;
import static io.prestosql.util.DateTimeUtils.parseTimestampLiteral;
import static io.prestosql.util.DateTimeUtils.timeHasTimeZone;
import static io.prestosql.util.DateTimeUtils.timestampHasTimeZone;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

public class ExpressionAnalyzer
{
    private static final int MAX_NUMBER_GROUPING_ARGUMENTS_BIGINT = 63;
    private static final int MAX_NUMBER_GROUPING_ARGUMENTS_INTEGER = 31;

    private final Metadata metadata;
    private final Function<Node, StatementAnalyzer> statementAnalyzerFactory;
    private final TypeProvider symbolTypes;
    private final boolean isDescribe;

    private final Map<NodeRef<FunctionCall>, Signature> resolvedFunctions = new LinkedHashMap<>();
    private final Set<NodeRef<SubqueryExpression>> scalarSubqueries = new LinkedHashSet<>();
    private final Set<NodeRef<ExistsPredicate>> existsSubqueries = new LinkedHashSet<>();
    private final Map<NodeRef<Expression>, Type> expressionCoercions = new LinkedHashMap<>();
    private final Set<NodeRef<Expression>> typeOnlyCoercions = new LinkedHashSet<>();
    private final Set<NodeRef<InPredicate>> subqueryInPredicates = new LinkedHashSet<>();
    private final Map<NodeRef<Expression>, FieldId> columnReferences = new LinkedHashMap<>();
    private final Map<NodeRef<Expression>, Type> expressionTypes = new LinkedHashMap<>();
    private final Set<NodeRef<QuantifiedComparisonExpression>> quantifiedComparisons = new LinkedHashSet<>();
    // For lambda argument references, maps each QualifiedNameReference to the referenced LambdaArgumentDeclaration
    private final Map<NodeRef<Identifier>, LambdaArgumentDeclaration> lambdaArgumentReferences = new LinkedHashMap<>();
    private final Set<NodeRef<FunctionCall>> windowFunctions = new LinkedHashSet<>();
    private final Multimap<QualifiedObjectName, String> tableColumnReferences = HashMultimap.create();

    private final Session session;
    private final Map<NodeRef<Parameter>, Expression> parameters;
    private final WarningCollector warningCollector;
    private final TypeCoercion typeCoercion;

    public ExpressionAnalyzer(
            Metadata metadata,
            Function<Node, StatementAnalyzer> statementAnalyzerFactory,
            Session session,
            TypeProvider symbolTypes,
            Map<NodeRef<Parameter>, Expression> parameters,
            WarningCollector warningCollector,
            boolean isDescribe)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.statementAnalyzerFactory = requireNonNull(statementAnalyzerFactory, "statementAnalyzerFactory is null");
        this.session = requireNonNull(session, "session is null");
        this.symbolTypes = requireNonNull(symbolTypes, "symbolTypes is null");
        this.parameters = requireNonNull(parameters, "parameterMap is null");
        this.isDescribe = isDescribe;
        this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
        this.typeCoercion = new TypeCoercion(metadata::getType);
    }

    public Map<NodeRef<FunctionCall>, Signature> getResolvedFunctions()
    {
        return unmodifiableMap(resolvedFunctions);
    }

    public Map<NodeRef<Expression>, Type> getExpressionTypes()
    {
        return unmodifiableMap(expressionTypes);
    }

    public Type setExpressionType(Expression expression, Type type)
    {
        requireNonNull(expression, "expression cannot be null");
        requireNonNull(type, "type cannot be null");

        expressionTypes.put(NodeRef.of(expression), type);

        return type;
    }

    private Type getExpressionType(Expression expression)
    {
        requireNonNull(expression, "expression cannot be null");

        Type type = expressionTypes.get(NodeRef.of(expression));
        checkState(type != null, "Expression not yet analyzed: %s", expression);
        return type;
    }

    public Map<NodeRef<Expression>, Type> getExpressionCoercions()
    {
        return unmodifiableMap(expressionCoercions);
    }

    public Set<NodeRef<Expression>> getTypeOnlyCoercions()
    {
        return unmodifiableSet(typeOnlyCoercions);
    }

    public Set<NodeRef<InPredicate>> getSubqueryInPredicates()
    {
        return unmodifiableSet(subqueryInPredicates);
    }

    public Map<NodeRef<Expression>, FieldId> getColumnReferences()
    {
        return unmodifiableMap(columnReferences);
    }

    public Map<NodeRef<Identifier>, LambdaArgumentDeclaration> getLambdaArgumentReferences()
    {
        return unmodifiableMap(lambdaArgumentReferences);
    }

    public Type analyze(Expression expression, Scope scope)
    {
        Visitor visitor = new Visitor(scope, warningCollector);
        return visitor.process(expression, new StackableAstVisitor.StackableAstVisitorContext<>(Context.notInLambda(scope)));
    }

    private Type analyze(Expression expression, Scope baseScope, Context context)
    {
        Visitor visitor = new Visitor(baseScope, warningCollector);
        return visitor.process(expression, new StackableAstVisitor.StackableAstVisitorContext<>(context));
    }

    private List<TypeSignatureProvider> getCallArgumentTypes(List<Expression> arguments, Scope scope)
    {
        Visitor visitor = new Visitor(scope, warningCollector);
        return visitor.getCallArgumentTypes(arguments, new StackableAstVisitor.StackableAstVisitorContext<>(Context.notInLambda(scope)));
    }

    public Set<NodeRef<SubqueryExpression>> getScalarSubqueries()
    {
        return unmodifiableSet(scalarSubqueries);
    }

    public Set<NodeRef<ExistsPredicate>> getExistsSubqueries()
    {
        return unmodifiableSet(existsSubqueries);
    }

    public Set<NodeRef<QuantifiedComparisonExpression>> getQuantifiedComparisons()
    {
        return unmodifiableSet(quantifiedComparisons);
    }

    public Set<NodeRef<FunctionCall>> getWindowFunctions()
    {
        return unmodifiableSet(windowFunctions);
    }

    public Multimap<QualifiedObjectName, String> getTableColumnReferences()
    {
        return tableColumnReferences;
    }

    private class Visitor
            extends StackableAstVisitor<Type, Context>
    {
        // Used to resolve FieldReferences (e.g. during local execution planning)
        private final Scope baseScope;
        private final WarningCollector warningCollector;

        public Visitor(Scope baseScope, WarningCollector warningCollector)
        {
            this.baseScope = requireNonNull(baseScope, "baseScope is null");
            this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
        }

        @Override
        public Type process(Node node, @Nullable StackableAstVisitorContext<Context> context)
        {
            if (node instanceof Expression) {
                // don't double process a node
                Type type = expressionTypes.get(NodeRef.of(((Expression) node)));
                if (type != null) {
                    return type;
                }
            }
            return super.process(node, context);
        }

        @Override
        protected Type visitRow(Row node, StackableAstVisitorContext<Context> context)
        {
            List<Type> types = node.getItems().stream()
                    .map((child) -> process(child, context))
                    .collect(toImmutableList());

            Type type = RowType.anonymous(types);
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitCurrentTime(CurrentTime node, StackableAstVisitorContext<Context> context)
        {
            if (node.getPrecision() != null) {
                throw semanticException(NOT_SUPPORTED, node, "non-default precision not yet supported");
            }

            Type type;
            switch (node.getFunction()) {
                case DATE:
                    type = DATE;
                    break;
                case TIME:
                    type = TIME_WITH_TIME_ZONE;
                    break;
                case LOCALTIME:
                    type = TIME;
                    break;
                case TIMESTAMP:
                    type = TIMESTAMP_WITH_TIME_ZONE;
                    break;
                case LOCALTIMESTAMP:
                    type = TIMESTAMP;
                    break;
                default:
                    throw semanticException(NOT_SUPPORTED, node, "%s not yet supported", node.getFunction().getName());
            }

            return setExpressionType(node, type);
        }

        @Override
        protected Type visitSymbolReference(SymbolReference node, StackableAstVisitorContext<Context> context)
        {
            if (context.getContext().isInLambda()) {
                Optional<ResolvedField> resolvedField = context.getContext().getScope().tryResolveField(node, QualifiedName.of(node.getName()));
                if (resolvedField.isPresent() && context.getContext().getFieldToLambdaArgumentDeclaration().containsKey(FieldId.from(resolvedField.get()))) {
                    return setExpressionType(node, resolvedField.get().getType());
                }
            }
            Type type = symbolTypes.get(Symbol.from(node));
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitIdentifier(Identifier node, StackableAstVisitorContext<Context> context)
        {
            ResolvedField resolvedField = context.getContext().getScope().resolveField(node, QualifiedName.of(node.getValue()));
            return handleResolvedField(node, resolvedField, context);
        }

        private Type handleResolvedField(Expression node, ResolvedField resolvedField, StackableAstVisitorContext<Context> context)
        {
            return handleResolvedField(node, FieldId.from(resolvedField), resolvedField.getField(), context);
        }

        private Type handleResolvedField(Expression node, FieldId fieldId, Field field, StackableAstVisitorContext<Context> context)
        {
            if (context.getContext().isInLambda()) {
                LambdaArgumentDeclaration lambdaArgumentDeclaration = context.getContext().getFieldToLambdaArgumentDeclaration().get(fieldId);
                if (lambdaArgumentDeclaration != null) {
                    // Lambda argument reference is not a column reference
                    lambdaArgumentReferences.put(NodeRef.of((Identifier) node), lambdaArgumentDeclaration);
                    return setExpressionType(node, field.getType());
                }
            }

            if (field.getOriginTable().isPresent() && field.getOriginColumnName().isPresent()) {
                tableColumnReferences.put(field.getOriginTable().get(), field.getOriginColumnName().get());
            }

            FieldId previous = columnReferences.put(NodeRef.of(node), fieldId);
            checkState(previous == null, "%s already known to refer to %s", node, previous);
            return setExpressionType(node, field.getType());
        }

        @Override
        protected Type visitDereferenceExpression(DereferenceExpression node, StackableAstVisitorContext<Context> context)
        {
            QualifiedName qualifiedName = DereferenceExpression.getQualifiedName(node);

            // If this Dereference looks like column reference, try match it to column first.
            if (qualifiedName != null) {
                Scope scope = context.getContext().getScope();
                Optional<ResolvedField> resolvedField = scope.tryResolveField(node, qualifiedName);
                if (resolvedField.isPresent()) {
                    return handleResolvedField(node, resolvedField.get(), context);
                }
                if (!scope.isColumnReference(qualifiedName)) {
                    throw missingAttributeException(node, qualifiedName);
                }
            }

            Type baseType = process(node.getBase(), context);
            if (!(baseType instanceof RowType)) {
                throw semanticException(TYPE_MISMATCH, node.getBase(), "Expression %s is not of type ROW", node.getBase());
            }

            RowType rowType = (RowType) baseType;
            String fieldName = node.getField().getValue();

            Type rowFieldType = null;
            for (RowType.Field rowField : rowType.getFields()) {
                if (fieldName.equalsIgnoreCase(rowField.getName().orElse(null))) {
                    rowFieldType = rowField.getType();
                    break;
                }
            }

            if (rowFieldType == null) {
                throw missingAttributeException(node);
            }

            return setExpressionType(node, rowFieldType);
        }

        @Override
        protected Type visitNotExpression(NotExpression node, StackableAstVisitorContext<Context> context)
        {
            coerceType(context, node.getValue(), BOOLEAN, "Value of logical NOT expression");

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitLogicalBinaryExpression(LogicalBinaryExpression node, StackableAstVisitorContext<Context> context)
        {
            coerceType(context, node.getLeft(), BOOLEAN, "Left side of logical expression");
            coerceType(context, node.getRight(), BOOLEAN, "Right side of logical expression");

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitComparisonExpression(ComparisonExpression node, StackableAstVisitorContext<Context> context)
        {
            OperatorType operatorType = OperatorType.valueOf(node.getOperator().name());
            return getOperator(context, node, operatorType, node.getLeft(), node.getRight());
        }

        @Override
        protected Type visitIsNullPredicate(IsNullPredicate node, StackableAstVisitorContext<Context> context)
        {
            process(node.getValue(), context);

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitIsNotNullPredicate(IsNotNullPredicate node, StackableAstVisitorContext<Context> context)
        {
            process(node.getValue(), context);

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitNullIfExpression(NullIfExpression node, StackableAstVisitorContext<Context> context)
        {
            Type firstType = process(node.getFirst(), context);
            Type secondType = process(node.getSecond(), context);

            if (!typeCoercion.getCommonSuperType(firstType, secondType).isPresent()) {
                throw semanticException(TYPE_MISMATCH, node, "Types are not comparable with NULLIF: %s vs %s", firstType, secondType);
            }

            return setExpressionType(node, firstType);
        }

        @Override
        protected Type visitIfExpression(IfExpression node, StackableAstVisitorContext<Context> context)
        {
            coerceType(context, node.getCondition(), BOOLEAN, "IF condition");

            Type type;
            if (node.getFalseValue().isPresent()) {
                type = coerceToSingleType(context, node, "Result types for IF must be the same: %s vs %s", node.getTrueValue(), node.getFalseValue().get());
            }
            else {
                type = process(node.getTrueValue(), context);
            }

            return setExpressionType(node, type);
        }

        @Override
        protected Type visitSearchedCaseExpression(SearchedCaseExpression node, StackableAstVisitorContext<Context> context)
        {
            for (WhenClause whenClause : node.getWhenClauses()) {
                coerceType(context, whenClause.getOperand(), BOOLEAN, "CASE WHEN clause");
            }

            Type type = coerceToSingleType(context,
                    "All CASE results must be the same type: %s",
                    getCaseResultExpressions(node.getWhenClauses(), node.getDefaultValue()));
            setExpressionType(node, type);

            for (WhenClause whenClause : node.getWhenClauses()) {
                Type whenClauseType = process(whenClause.getResult(), context);
                setExpressionType(whenClause, whenClauseType);
            }

            return type;
        }

        @Override
        protected Type visitSimpleCaseExpression(SimpleCaseExpression node, StackableAstVisitorContext<Context> context)
        {
            for (WhenClause whenClause : node.getWhenClauses()) {
                coerceToSingleType(context, whenClause, "CASE operand type does not match WHEN clause operand type: %s vs %s", node.getOperand(), whenClause.getOperand());
            }

            Type type = coerceToSingleType(context,
                    "All CASE results must be the same type: %s",
                    getCaseResultExpressions(node.getWhenClauses(), node.getDefaultValue()));
            setExpressionType(node, type);

            for (WhenClause whenClause : node.getWhenClauses()) {
                Type whenClauseType = process(whenClause.getResult(), context);
                setExpressionType(whenClause, whenClauseType);
            }

            return type;
        }

        private List<Expression> getCaseResultExpressions(List<WhenClause> whenClauses, Optional<Expression> defaultValue)
        {
            List<Expression> resultExpressions = new ArrayList<>();
            for (WhenClause whenClause : whenClauses) {
                resultExpressions.add(whenClause.getResult());
            }
            defaultValue.ifPresent(resultExpressions::add);
            return resultExpressions;
        }

        @Override
        protected Type visitCoalesceExpression(CoalesceExpression node, StackableAstVisitorContext<Context> context)
        {
            Type type = coerceToSingleType(context, "All COALESCE operands must be the same type: %s", node.getOperands());

            return setExpressionType(node, type);
        }

        @Override
        protected Type visitArithmeticUnary(ArithmeticUnaryExpression node, StackableAstVisitorContext<Context> context)
        {
            switch (node.getSign()) {
                case PLUS:
                    Type type = process(node.getValue(), context);

                    if (!type.equals(DOUBLE) && !type.equals(REAL) && !type.equals(BIGINT) && !type.equals(INTEGER) && !type.equals(SMALLINT) && !type.equals(TINYINT)) {
                        // TODO: figure out a type-agnostic way of dealing with this. Maybe add a special unary operator
                        // that types can chose to implement, or piggyback on the existence of the negation operator
                        throw semanticException(TYPE_MISMATCH, node, "Unary '+' operator cannot by applied to %s type", type);
                    }
                    return setExpressionType(node, type);
                case MINUS:
                    return getOperator(context, node, OperatorType.NEGATION, node.getValue());
            }

            throw new UnsupportedOperationException("Unsupported unary operator: " + node.getSign());
        }

        @Override
        protected Type visitArithmeticBinary(ArithmeticBinaryExpression node, StackableAstVisitorContext<Context> context)
        {
            return getOperator(context, node, OperatorType.valueOf(node.getOperator().name()), node.getLeft(), node.getRight());
        }

        @Override
        protected Type visitLikePredicate(LikePredicate node, StackableAstVisitorContext<Context> context)
        {
            Type valueType = process(node.getValue(), context);
            if (!(valueType instanceof CharType) && !(valueType instanceof VarcharType)) {
                coerceType(context, node.getValue(), VARCHAR, "Left side of LIKE expression");
            }

            Type patternType = getVarcharType(node.getPattern(), context);
            coerceType(context, node.getPattern(), patternType, "Pattern for LIKE expression");
            if (node.getEscape().isPresent()) {
                Expression escape = node.getEscape().get();
                Type escapeType = getVarcharType(escape, context);
                coerceType(context, escape, escapeType, "Escape for LIKE expression");
            }

            return setExpressionType(node, BOOLEAN);
        }

        private Type getVarcharType(Expression value, StackableAstVisitorContext<Context> context)
        {
            Type type = process(value, context);
            if (!(type instanceof VarcharType)) {
                return VARCHAR;
            }
            return type;
        }

        @Override
        protected Type visitSubscriptExpression(SubscriptExpression node, StackableAstVisitorContext<Context> context)
        {
            Type baseType = process(node.getBase(), context);
            // Subscript on Row hasn't got a dedicated operator. Its Type is resolved by hand.
            if (baseType instanceof RowType) {
                if (!(node.getIndex() instanceof LongLiteral)) {
                    throw semanticException(EXPRESSION_NOT_CONSTANT, node.getIndex(), "Subscript expression on ROW requires a constant index");
                }
                Type indexType = process(node.getIndex(), context);
                if (!indexType.equals(INTEGER)) {
                    throw semanticException(TYPE_MISMATCH, node.getIndex(), "Subscript expression on ROW requires integer index, found %s", indexType);
                }
                int indexValue = toIntExact(((LongLiteral) node.getIndex()).getValue());
                if (indexValue <= 0) {
                    throw semanticException(INVALID_FUNCTION_ARGUMENT, node.getIndex(), "Invalid subscript index: %s. ROW indices start at 1", indexValue);
                }
                List<Type> rowTypes = baseType.getTypeParameters();
                if (indexValue > rowTypes.size()) {
                    throw semanticException(INVALID_FUNCTION_ARGUMENT, node.getIndex(), "Subscript index out of bounds: %s, max value is %s", indexValue, rowTypes.size());
                }
                return setExpressionType(node, rowTypes.get(indexValue - 1));
            }

            // Subscript on Array or Map uses an operator to resolve Type.
            return getOperator(context, node, SUBSCRIPT, node.getBase(), node.getIndex());
        }

        @Override
        protected Type visitArrayConstructor(ArrayConstructor node, StackableAstVisitorContext<Context> context)
        {
            Type type = coerceToSingleType(context, "All ARRAY elements must be the same type: %s", node.getValues());
            Type arrayType = metadata.getParameterizedType(ARRAY.getName(), ImmutableList.of(TypeSignatureParameter.of(type.getTypeSignature())));
            return setExpressionType(node, arrayType);
        }

        @Override
        protected Type visitStringLiteral(StringLiteral node, StackableAstVisitorContext<Context> context)
        {
            VarcharType type = VarcharType.createVarcharType(SliceUtf8.countCodePoints(node.getSlice()));
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitCharLiteral(CharLiteral node, StackableAstVisitorContext<Context> context)
        {
            CharType type = CharType.createCharType(node.getValue().length());
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitBinaryLiteral(BinaryLiteral node, StackableAstVisitorContext<Context> context)
        {
            return setExpressionType(node, VARBINARY);
        }

        @Override
        protected Type visitLongLiteral(LongLiteral node, StackableAstVisitorContext<Context> context)
        {
            if (node.getValue() >= Integer.MIN_VALUE && node.getValue() <= Integer.MAX_VALUE) {
                return setExpressionType(node, INTEGER);
            }

            return setExpressionType(node, BIGINT);
        }

        @Override
        protected Type visitDoubleLiteral(DoubleLiteral node, StackableAstVisitorContext<Context> context)
        {
            return setExpressionType(node, DOUBLE);
        }

        @Override
        protected Type visitDecimalLiteral(DecimalLiteral node, StackableAstVisitorContext<Context> context)
        {
            DecimalParseResult parseResult = Decimals.parse(node.getValue());
            return setExpressionType(node, parseResult.getType());
        }

        @Override
        protected Type visitBooleanLiteral(BooleanLiteral node, StackableAstVisitorContext<Context> context)
        {
            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitGenericLiteral(GenericLiteral node, StackableAstVisitorContext<Context> context)
        {
            Type type;
            try {
                type = metadata.fromSqlType(node.getType());
            }
            catch (TypeNotFoundException e) {
                throw semanticException(TYPE_NOT_FOUND, node, "Unknown type: " + node.getType());
            }

            if (!JSON.equals(type)) {
                try {
                    metadata.getCoercion(VARCHAR, type);
                }
                catch (IllegalArgumentException e) {
                    throw semanticException(INVALID_LITERAL, node, "No literal form for type %s", type);
                }
            }

            return setExpressionType(node, type);
        }

        @Override
        protected Type visitTimeLiteral(TimeLiteral node, StackableAstVisitorContext<Context> context)
        {
            boolean hasTimeZone;
            try {
                hasTimeZone = timeHasTimeZone(node.getValue());
            }
            catch (IllegalArgumentException e) {
                throw semanticException(INVALID_LITERAL, node, "'%s' is not a valid time literal", node.getValue());
            }
            Type type = hasTimeZone ? TIME_WITH_TIME_ZONE : TIME;
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitTimestampLiteral(TimestampLiteral node, StackableAstVisitorContext<Context> context)
        {
            try {
                if (SystemSessionProperties.isLegacyTimestamp(session)) {
                    parseTimestampLiteral(session.getTimeZoneKey(), node.getValue());
                }
                else {
                    parseTimestampLiteral(node.getValue());
                }
            }
            catch (Exception e) {
                throw semanticException(INVALID_LITERAL, node, "'%s' is not a valid timestamp literal", node.getValue());
            }

            Type type;
            if (timestampHasTimeZone(node.getValue())) {
                type = TIMESTAMP_WITH_TIME_ZONE;
            }
            else {
                type = TIMESTAMP;
            }
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitIntervalLiteral(IntervalLiteral node, StackableAstVisitorContext<Context> context)
        {
            Type type;
            if (node.isYearToMonth()) {
                type = INTERVAL_YEAR_MONTH;
            }
            else {
                type = INTERVAL_DAY_TIME;
            }
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitNullLiteral(NullLiteral node, StackableAstVisitorContext<Context> context)
        {
            return setExpressionType(node, UNKNOWN);
        }

        @Override
        protected Type visitFunctionCall(FunctionCall node, StackableAstVisitorContext<Context> context)
        {
            if (node.getWindow().isPresent()) {
                for (Expression expression : node.getWindow().get().getPartitionBy()) {
                    process(expression, context);
                    Type type = getExpressionType(expression);
                    if (!type.isComparable()) {
                        throw semanticException(TYPE_MISMATCH, node, "%s is not comparable, and therefore cannot be used in window function PARTITION BY", type);
                    }
                }

                for (SortItem sortItem : getSortItemsFromOrderBy(node.getWindow().get().getOrderBy())) {
                    process(sortItem.getSortKey(), context);
                    Type type = getExpressionType(sortItem.getSortKey());
                    if (!type.isOrderable()) {
                        throw semanticException(TYPE_MISMATCH, node, "%s is not orderable, and therefore cannot be used in window function ORDER BY", type);
                    }
                }

                if (node.getWindow().get().getFrame().isPresent()) {
                    WindowFrame frame = node.getWindow().get().getFrame().get();

                    if (frame.getStart().getValue().isPresent()) {
                        Type type = process(frame.getStart().getValue().get(), context);
                        if (!type.equals(INTEGER) && !type.equals(BIGINT)) {
                            throw semanticException(TYPE_MISMATCH, node, "Window frame start value type must be INTEGER or BIGINT(actual %s)", type);
                        }
                    }

                    if (frame.getEnd().isPresent() && frame.getEnd().get().getValue().isPresent()) {
                        Type type = process(frame.getEnd().get().getValue().get(), context);
                        if (!type.equals(INTEGER) && !type.equals(BIGINT)) {
                            throw semanticException(TYPE_MISMATCH, node, "Window frame end value type must be INTEGER or BIGINT (actual %s)", type);
                        }
                    }
                }

                windowFunctions.add(NodeRef.of(node));
            }

            if (node.getFilter().isPresent()) {
                Expression expression = node.getFilter().get();
                process(expression, context);
            }

            List<TypeSignatureProvider> argumentTypes = getCallArgumentTypes(node.getArguments(), context);

            Signature function;
            try {
                function = metadata.resolveFunction(node.getName(), argumentTypes);
            }
            catch (PrestoException e) {
                if (e.getLocation().isPresent()) {
                    // If analysis of any of the argument types (which is done lazily to deal with lambda
                    // expressions) fails, we want to report the original reason for the failure
                    throw e;
                }

                // otherwise, it must have failed due to a missing function or other reason, so we report an error at the
                // current location

                throw new PrestoException(e::getErrorCode, extractLocation(node), e.getMessage(), e);
            }

            if (function.getName().equalsIgnoreCase(ARRAY_CONSTRUCTOR)) {
                // After optimization, array constructor is rewritten to a function call.
                // For historic reasons array constructor is allowed to have 254 arguments
                if (node.getArguments().size() > 254) {
                    throw semanticException(TOO_MANY_ARGUMENTS, node, "Too many arguments for array constructor", function.getName());
                }
            }
            else if (node.getArguments().size() > 127) {
                throw semanticException(TOO_MANY_ARGUMENTS, node, "Too many arguments for function call %s()", function.getName());
            }

            if (node.getOrderBy().isPresent()) {
                for (SortItem sortItem : node.getOrderBy().get().getSortItems()) {
                    Type sortKeyType = process(sortItem.getSortKey(), context);
                    if (!sortKeyType.isOrderable()) {
                        throw semanticException(TYPE_MISMATCH, node, "ORDER BY can only be applied to orderable types (actual: %s)", sortKeyType.getDisplayName());
                    }
                }
            }

            for (int i = 0; i < node.getArguments().size(); i++) {
                Expression expression = node.getArguments().get(i);
                Type expectedType = metadata.getType(function.getArgumentTypes().get(i));
                requireNonNull(expectedType, format("Type %s not found", function.getArgumentTypes().get(i)));
                if (node.isDistinct() && !expectedType.isComparable()) {
                    throw semanticException(TYPE_MISMATCH, node, "DISTINCT can only be applied to comparable types (actual: %s)", expectedType);
                }
                if (argumentTypes.get(i).hasDependency()) {
                    FunctionType expectedFunctionType = (FunctionType) expectedType;
                    process(expression, new StackableAstVisitorContext<>(context.getContext().expectingLambda(expectedFunctionType.getArgumentTypes())));
                }
                else {
                    Type actualType = metadata.getType(argumentTypes.get(i).getTypeSignature());
                    coerceType(expression, actualType, expectedType, format("Function %s argument %d", function, i));
                }
            }
            resolvedFunctions.put(NodeRef.of(node), function);

            Type type = metadata.getType(function.getReturnType());
            return setExpressionType(node, type);
        }

        public List<TypeSignatureProvider> getCallArgumentTypes(List<Expression> arguments, StackableAstVisitorContext<Context> context)
        {
            ImmutableList.Builder<TypeSignatureProvider> argumentTypesBuilder = ImmutableList.builder();
            for (Expression argument : arguments) {
                if (argument instanceof LambdaExpression || argument instanceof BindExpression) {
                    argumentTypesBuilder.add(new TypeSignatureProvider(
                            types -> {
                                ExpressionAnalyzer innerExpressionAnalyzer = new ExpressionAnalyzer(
                                        metadata,
                                        statementAnalyzerFactory,
                                        session,
                                        symbolTypes,
                                        parameters,
                                        warningCollector,
                                        isDescribe);
                                if (context.getContext().isInLambda()) {
                                    for (LambdaArgumentDeclaration lambdaArgument : context.getContext().getFieldToLambdaArgumentDeclaration().values()) {
                                        innerExpressionAnalyzer.setExpressionType(lambdaArgument, getExpressionType(lambdaArgument));
                                    }
                                }
                                return innerExpressionAnalyzer.analyze(argument, baseScope, context.getContext().expectingLambda(types)).getTypeSignature();
                            }));
                }
                else {
                    argumentTypesBuilder.add(new TypeSignatureProvider(process(argument, context).getTypeSignature()));
                }
            }

            return argumentTypesBuilder.build();
        }

        @Override
        protected Type visitAtTimeZone(AtTimeZone node, StackableAstVisitorContext<Context> context)
        {
            Type valueType = process(node.getValue(), context);
            process(node.getTimeZone(), context);
            if (!valueType.equals(TIME_WITH_TIME_ZONE) && !valueType.equals(TIMESTAMP_WITH_TIME_ZONE) && !valueType.equals(TIME) && !valueType.equals(TIMESTAMP)) {
                throw semanticException(TYPE_MISMATCH, node.getValue(), "Type of value must be a time or timestamp with or without time zone (actual %s)", valueType);
            }
            Type resultType = valueType;
            if (valueType.equals(TIME)) {
                resultType = TIME_WITH_TIME_ZONE;
            }
            else if (valueType.equals(TIMESTAMP)) {
                resultType = TIMESTAMP_WITH_TIME_ZONE;
            }

            return setExpressionType(node, resultType);
        }

        @Override
        protected Type visitCurrentUser(CurrentUser node, StackableAstVisitorContext<Context> context)
        {
            return setExpressionType(node, VARCHAR);
        }

        @Override
        protected Type visitCurrentPath(CurrentPath node, StackableAstVisitorContext<Context> context)
        {
            return setExpressionType(node, VARCHAR);
        }

        @Override
        protected Type visitFormat(Format node, StackableAstVisitorContext<Context> context)
        {
            List<Type> arguments = node.getArguments().stream()
                    .map(expression -> process(expression, context))
                    .collect(toImmutableList());

            if (!isVarcharType(arguments.get(0))) {
                throw semanticException(TYPE_MISMATCH, node.getArguments().get(0), "Type of first argument to format() must be VARCHAR (actual: %s)", arguments.get(0));
            }

            for (int i = 1; i < arguments.size(); i++) {
                try {
                    FormatFunction.validateType(metadata, arguments.get(i));
                }
                catch (PrestoException e) {
                    if (e.getErrorCode().equals(NOT_SUPPORTED.toErrorCode())) {
                        throw semanticException(NOT_SUPPORTED, node.getArguments().get(i), "%s", e.getRawMessage());
                    }
                    throw e;
                }
            }

            return setExpressionType(node, VARCHAR);
        }

        @Override
        protected Type visitParameter(Parameter node, StackableAstVisitorContext<Context> context)
        {
            if (isDescribe) {
                return setExpressionType(node, UNKNOWN);
            }
            if (parameters.size() == 0) {
                throw semanticException(INVALID_PARAMETER_USAGE, node, "Query takes no parameters");
            }
            if (node.getPosition() >= parameters.size()) {
                throw semanticException(INVALID_PARAMETER_USAGE, node, "Invalid parameter index %s, max value is %s", node.getPosition(), parameters.size() - 1);
            }

            Type resultType = process(parameters.get(NodeRef.of(node)), context);
            return setExpressionType(node, resultType);
        }

        @Override
        protected Type visitExtract(Extract node, StackableAstVisitorContext<Context> context)
        {
            Type type = process(node.getExpression(), context);
            if (!isDateTimeType(type)) {
                throw semanticException(TYPE_MISMATCH, node.getExpression(), "Type of argument to extract must be DATE, TIME, TIMESTAMP, or INTERVAL (actual %s)", type);
            }
            Extract.Field field = node.getField();
            if ((field == TIMEZONE_HOUR || field == TIMEZONE_MINUTE) && !(type.equals(TIME_WITH_TIME_ZONE) || type.equals(TIMESTAMP_WITH_TIME_ZONE))) {
                throw semanticException(TYPE_MISMATCH, node.getExpression(), "Type of argument to extract time zone field must have a time zone (actual %s)", type);
            }

            return setExpressionType(node, BIGINT);
        }

        private boolean isDateTimeType(Type type)
        {
            return type.equals(DATE) ||
                    type.equals(TIME) ||
                    type.equals(TIME_WITH_TIME_ZONE) ||
                    type.equals(TIMESTAMP) ||
                    type.equals(TIMESTAMP_WITH_TIME_ZONE) ||
                    type.equals(INTERVAL_DAY_TIME) ||
                    type.equals(INTERVAL_YEAR_MONTH);
        }

        @Override
        protected Type visitBetweenPredicate(BetweenPredicate node, StackableAstVisitorContext<Context> context)
        {
            return getOperator(context, node, OperatorType.BETWEEN, node.getValue(), node.getMin(), node.getMax());
        }

        @Override
        public Type visitTryExpression(TryExpression node, StackableAstVisitorContext<Context> context)
        {
            Type type = process(node.getInnerExpression(), context);
            return setExpressionType(node, type);
        }

        @Override
        public Type visitCast(Cast node, StackableAstVisitorContext<Context> context)
        {
            Type type;
            try {
                type = metadata.fromSqlType(node.getType());
            }
            catch (TypeNotFoundException e) {
                throw semanticException(TYPE_MISMATCH, node, "Unknown type: " + node.getType());
            }

            if (type.equals(UNKNOWN)) {
                throw semanticException(TYPE_MISMATCH, node, "UNKNOWN is not a valid type");
            }

            Type value = process(node.getExpression(), context);
            if (!value.equals(UNKNOWN) && !node.isTypeOnly()) {
                try {
                    metadata.getCoercion(value, type);
                }
                catch (OperatorNotFoundException e) {
                    throw semanticException(TYPE_MISMATCH, node, "Cannot cast %s to %s", value, type);
                }
            }

            return setExpressionType(node, type);
        }

        @Override
        protected Type visitInPredicate(InPredicate node, StackableAstVisitorContext<Context> context)
        {
            Expression value = node.getValue();
            process(value, context);

            Expression valueList = node.getValueList();
            process(valueList, context);

            if (valueList instanceof InListExpression) {
                InListExpression inListExpression = (InListExpression) valueList;

                coerceToSingleType(context,
                        "IN value and list items must be the same type: %s",
                        ImmutableList.<Expression>builder().add(value).addAll(inListExpression.getValues()).build());
            }
            else if (valueList instanceof SubqueryExpression) {
                coerceToSingleType(context, node, "value and result of subquery must be of the same type for IN expression: %s vs %s", value, valueList);
            }

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitInListExpression(InListExpression node, StackableAstVisitorContext<Context> context)
        {
            Type type = coerceToSingleType(context, "All IN list values must be the same type: %s", node.getValues());

            setExpressionType(node, type);
            return type; // TODO: this really should a be relation type
        }

        @Override
        protected Type visitSubqueryExpression(SubqueryExpression node, StackableAstVisitorContext<Context> context)
        {
            if (context.getContext().isInLambda()) {
                throw semanticException(NOT_SUPPORTED, node, "Lambda expression cannot contain subqueries");
            }
            StatementAnalyzer analyzer = statementAnalyzerFactory.apply(node);
            Scope subqueryScope = Scope.builder()
                    .withParent(context.getContext().getScope())
                    .build();
            Scope queryScope = analyzer.analyze(node.getQuery(), subqueryScope);

            // Subquery should only produce one column
            if (queryScope.getRelationType().getVisibleFieldCount() != 1) {
                throw semanticException(NOT_SUPPORTED,
                        node,
                        "Multiple columns returned by subquery are not yet supported. Found %s",
                        queryScope.getRelationType().getVisibleFieldCount());
            }

            Node previousNode = context.getPreviousNode().orElse(null);
            if (previousNode instanceof InPredicate && ((InPredicate) previousNode).getValue() != node) {
                subqueryInPredicates.add(NodeRef.of((InPredicate) previousNode));
            }
            else if (previousNode instanceof QuantifiedComparisonExpression) {
                quantifiedComparisons.add(NodeRef.of((QuantifiedComparisonExpression) previousNode));
            }
            else {
                scalarSubqueries.add(NodeRef.of(node));
            }

            Type type = getOnlyElement(queryScope.getRelationType().getVisibleFields()).getType();
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitExists(ExistsPredicate node, StackableAstVisitorContext<Context> context)
        {
            StatementAnalyzer analyzer = statementAnalyzerFactory.apply(node);
            Scope subqueryScope = Scope.builder().withParent(context.getContext().getScope()).build();
            analyzer.analyze(node.getSubquery(), subqueryScope);

            existsSubqueries.add(NodeRef.of(node));

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitQuantifiedComparisonExpression(QuantifiedComparisonExpression node, StackableAstVisitorContext<Context> context)
        {
            Expression value = node.getValue();
            process(value, context);

            Expression subquery = node.getSubquery();
            process(subquery, context);

            Type comparisonType = coerceToSingleType(context, node, "Value expression and result of subquery must be of the same type for quantified comparison: %s vs %s", value, subquery);

            switch (node.getOperator()) {
                case LESS_THAN:
                case LESS_THAN_OR_EQUAL:
                case GREATER_THAN:
                case GREATER_THAN_OR_EQUAL:
                    if (!comparisonType.isOrderable()) {
                        throw semanticException(TYPE_MISMATCH, node, "Type [%s] must be orderable in order to be used in quantified comparison", comparisonType);
                    }
                    break;
                case EQUAL:
                case NOT_EQUAL:
                    if (!comparisonType.isComparable()) {
                        throw semanticException(TYPE_MISMATCH, node, "Type [%s] must be comparable in order to be used in quantified comparison", comparisonType);
                    }
                    break;
                default:
                    throw new IllegalStateException(format("Unexpected comparison type: %s", node.getOperator()));
            }

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        public Type visitFieldReference(FieldReference node, StackableAstVisitorContext<Context> context)
        {
            Field field = baseScope.getRelationType().getFieldByIndex(node.getFieldIndex());
            return handleResolvedField(node, new FieldId(baseScope.getRelationId(), node.getFieldIndex()), field, context);
        }

        @Override
        protected Type visitLambdaExpression(LambdaExpression node, StackableAstVisitorContext<Context> context)
        {
            verifyNoAggregateWindowOrGroupingFunctions(metadata, node.getBody(), "Lambda expression");
            if (!context.getContext().isExpectingLambda()) {
                throw semanticException(TYPE_MISMATCH, node, "Lambda expression should always be used inside a function");
            }

            List<Type> types = context.getContext().getFunctionInputTypes();
            List<LambdaArgumentDeclaration> lambdaArguments = node.getArguments();

            if (types.size() != lambdaArguments.size()) {
                throw semanticException(INVALID_PARAMETER_USAGE, node,
                        format("Expected a lambda that takes %s argument(s) but got %s", types.size(), lambdaArguments.size()));
            }

            ImmutableList.Builder<Field> fields = ImmutableList.builder();
            for (int i = 0; i < lambdaArguments.size(); i++) {
                LambdaArgumentDeclaration lambdaArgument = lambdaArguments.get(i);
                Type type = types.get(i);
                fields.add(io.prestosql.sql.analyzer.Field.newUnqualified(lambdaArgument.getName().getValue(), type));
                setExpressionType(lambdaArgument, type);
            }

            Scope lambdaScope = Scope.builder()
                    .withParent(context.getContext().getScope())
                    .withRelationType(RelationId.of(node), new RelationType(fields.build()))
                    .build();

            ImmutableMap.Builder<FieldId, LambdaArgumentDeclaration> fieldToLambdaArgumentDeclaration = ImmutableMap.builder();
            if (context.getContext().isInLambda()) {
                fieldToLambdaArgumentDeclaration.putAll(context.getContext().getFieldToLambdaArgumentDeclaration());
            }
            for (LambdaArgumentDeclaration lambdaArgument : lambdaArguments) {
                ResolvedField resolvedField = lambdaScope.resolveField(lambdaArgument, QualifiedName.of(lambdaArgument.getName().getValue()));
                fieldToLambdaArgumentDeclaration.put(FieldId.from(resolvedField), lambdaArgument);
            }

            Type returnType = process(node.getBody(), new StackableAstVisitorContext<>(Context.inLambda(lambdaScope, fieldToLambdaArgumentDeclaration.build())));
            FunctionType functionType = new FunctionType(types, returnType);
            return setExpressionType(node, functionType);
        }

        @Override
        protected Type visitBindExpression(BindExpression node, StackableAstVisitorContext<Context> context)
        {
            verify(context.getContext().isExpectingLambda(), "bind expression found when lambda is not expected");

            StackableAstVisitorContext<Context> innerContext = new StackableAstVisitorContext<>(context.getContext().notExpectingLambda());
            ImmutableList.Builder<Type> functionInputTypesBuilder = ImmutableList.builder();
            for (Expression value : node.getValues()) {
                functionInputTypesBuilder.add(process(value, innerContext));
            }
            functionInputTypesBuilder.addAll(context.getContext().getFunctionInputTypes());
            List<Type> functionInputTypes = functionInputTypesBuilder.build();

            FunctionType functionType = (FunctionType) process(node.getFunction(), new StackableAstVisitorContext<>(context.getContext().expectingLambda(functionInputTypes)));

            List<Type> argumentTypes = functionType.getArgumentTypes();
            int numCapturedValues = node.getValues().size();
            verify(argumentTypes.size() == functionInputTypes.size());
            for (int i = 0; i < numCapturedValues; i++) {
                verify(functionInputTypes.get(i).equals(argumentTypes.get(i)));
            }

            FunctionType result = new FunctionType(argumentTypes.subList(numCapturedValues, argumentTypes.size()), functionType.getReturnType());
            return setExpressionType(node, result);
        }

        @Override
        protected Type visitExpression(Expression node, StackableAstVisitorContext<Context> context)
        {
            throw semanticException(NOT_SUPPORTED, node, "not yet implemented: " + node.getClass().getName());
        }

        @Override
        protected Type visitNode(Node node, StackableAstVisitorContext<Context> context)
        {
            throw semanticException(NOT_SUPPORTED, node, "not yet implemented: " + node.getClass().getName());
        }

        @Override
        public Type visitGroupingOperation(GroupingOperation node, StackableAstVisitorContext<Context> context)
        {
            if (node.getGroupingColumns().size() > MAX_NUMBER_GROUPING_ARGUMENTS_BIGINT) {
                throw semanticException(TOO_MANY_ARGUMENTS, node, format("GROUPING supports up to %d column arguments", MAX_NUMBER_GROUPING_ARGUMENTS_BIGINT));
            }

            for (Expression columnArgument : node.getGroupingColumns()) {
                process(columnArgument, context);
            }

            if (node.getGroupingColumns().size() <= MAX_NUMBER_GROUPING_ARGUMENTS_INTEGER) {
                return setExpressionType(node, INTEGER);
            }
            else {
                return setExpressionType(node, BIGINT);
            }
        }

        private Type getOperator(StackableAstVisitorContext<Context> context, Expression node, OperatorType operatorType, Expression... arguments)
        {
            ImmutableList.Builder<Type> argumentTypes = ImmutableList.builder();
            for (Expression expression : arguments) {
                argumentTypes.add(process(expression, context));
            }

            Signature operatorSignature;
            try {
                operatorSignature = metadata.resolveOperator(operatorType, argumentTypes.build());
            }
            catch (OperatorNotFoundException e) {
                throw semanticException(TYPE_MISMATCH, node, "%s", e.getMessage());
            }

            for (int i = 0; i < arguments.length; i++) {
                Expression expression = arguments[i];
                Type type = metadata.getType(operatorSignature.getArgumentTypes().get(i));
                coerceType(context, expression, type, format("Operator %s argument %d", operatorSignature, i));
            }

            Type type = metadata.getType(operatorSignature.getReturnType());
            return setExpressionType(node, type);
        }

        private void coerceType(Expression expression, Type actualType, Type expectedType, String message)
        {
            if (!actualType.equals(expectedType)) {
                if (!typeCoercion.canCoerce(actualType, expectedType)) {
                    throw semanticException(TYPE_MISMATCH, expression, message + " must evaluate to a %s (actual: %s)", expectedType, actualType);
                }
                addOrReplaceExpressionCoercion(expression, actualType, expectedType);
            }
        }

        private void coerceType(StackableAstVisitorContext<Context> context, Expression expression, Type expectedType, String message)
        {
            Type actualType = process(expression, context);
            coerceType(expression, actualType, expectedType, message);
        }

        private Type coerceToSingleType(StackableAstVisitorContext<Context> context, Node node, String message, Expression first, Expression second)
        {
            Type firstType = UNKNOWN;
            if (first != null) {
                firstType = process(first, context);
            }
            Type secondType = UNKNOWN;
            if (second != null) {
                secondType = process(second, context);
            }

            // coerce types if possible
            Optional<Type> superTypeOptional = typeCoercion.getCommonSuperType(firstType, secondType);
            if (superTypeOptional.isPresent()
                    && typeCoercion.canCoerce(firstType, superTypeOptional.get())
                    && typeCoercion.canCoerce(secondType, superTypeOptional.get())) {
                Type superType = superTypeOptional.get();
                if (!firstType.equals(superType)) {
                    addOrReplaceExpressionCoercion(first, firstType, superType);
                }
                if (!secondType.equals(superType)) {
                    addOrReplaceExpressionCoercion(second, secondType, superType);
                }
                return superType;
            }

            throw semanticException(TYPE_MISMATCH, node, message, firstType, secondType);
        }

        private Type coerceToSingleType(StackableAstVisitorContext<Context> context, String message, List<Expression> expressions)
        {
            // determine super type
            Type superType = UNKNOWN;
            for (Expression expression : expressions) {
                Optional<Type> newSuperType = typeCoercion.getCommonSuperType(superType, process(expression, context));
                if (!newSuperType.isPresent()) {
                    throw semanticException(TYPE_MISMATCH, expression, message, superType);
                }
                superType = newSuperType.get();
            }

            // verify all expressions can be coerced to the superType
            for (Expression expression : expressions) {
                Type type = process(expression, context);
                if (!type.equals(superType)) {
                    if (!typeCoercion.canCoerce(type, superType)) {
                        throw semanticException(TYPE_MISMATCH, expression, message, superType);
                    }
                    addOrReplaceExpressionCoercion(expression, type, superType);
                }
            }

            return superType;
        }

        private void addOrReplaceExpressionCoercion(Expression expression, Type type, Type superType)
        {
            NodeRef<Expression> ref = NodeRef.of(expression);
            expressionCoercions.put(ref, superType);
            if (typeCoercion.isTypeOnlyCoercion(type, superType)) {
                typeOnlyCoercions.add(ref);
            }
            else if (typeOnlyCoercions.contains(ref)) {
                typeOnlyCoercions.remove(ref);
            }
        }
    }

    private static class Context
    {
        private final Scope scope;

        // functionInputTypes and nameToLambdaDeclarationMap can be null or non-null independently. All 4 combinations are possible.

        // The list of types when expecting a lambda (i.e. processing lambda parameters of a function); null otherwise.
        // Empty list represents expecting a lambda with no arguments.
        private final List<Type> functionInputTypes;
        // The mapping from names to corresponding lambda argument declarations when inside a lambda; null otherwise.
        // Empty map means that the all lambda expressions surrounding the current node has no arguments.
        private final Map<FieldId, LambdaArgumentDeclaration> fieldToLambdaArgumentDeclaration;

        private Context(
                Scope scope,
                List<Type> functionInputTypes,
                Map<FieldId, LambdaArgumentDeclaration> fieldToLambdaArgumentDeclaration)
        {
            this.scope = requireNonNull(scope, "scope is null");
            this.functionInputTypes = functionInputTypes;
            this.fieldToLambdaArgumentDeclaration = fieldToLambdaArgumentDeclaration;
        }

        public static Context notInLambda(Scope scope)
        {
            return new Context(scope, null, null);
        }

        public static Context inLambda(Scope scope, Map<FieldId, LambdaArgumentDeclaration> fieldToLambdaArgumentDeclaration)
        {
            return new Context(scope, null, requireNonNull(fieldToLambdaArgumentDeclaration, "fieldToLambdaArgumentDeclaration is null"));
        }

        public Context expectingLambda(List<Type> functionInputTypes)
        {
            return new Context(scope, requireNonNull(functionInputTypes, "functionInputTypes is null"), this.fieldToLambdaArgumentDeclaration);
        }

        public Context notExpectingLambda()
        {
            return new Context(scope, null, this.fieldToLambdaArgumentDeclaration);
        }

        Scope getScope()
        {
            return scope;
        }

        public boolean isInLambda()
        {
            return fieldToLambdaArgumentDeclaration != null;
        }

        public boolean isExpectingLambda()
        {
            return functionInputTypes != null;
        }

        public Map<FieldId, LambdaArgumentDeclaration> getFieldToLambdaArgumentDeclaration()
        {
            checkState(isInLambda());
            return fieldToLambdaArgumentDeclaration;
        }

        public List<Type> getFunctionInputTypes()
        {
            checkState(isExpectingLambda());
            return functionInputTypes;
        }
    }

    public static ExpressionAnalysis analyzeExpressions(
            Session session,
            Metadata metadata,
            SqlParser sqlParser,
            TypeProvider types,
            Iterable<Expression> expressions,
            Map<NodeRef<Parameter>, Expression> parameters,
            WarningCollector warningCollector,
            boolean isDescribe)
    {
        // expressions at this point can not have sub queries so deny all access checks
        // in the future, we will need a full access controller here to verify access to functions
        Analysis analysis = new Analysis(null, parameters, isDescribe);
        ExpressionAnalyzer analyzer = create(analysis, session, metadata, sqlParser, new DenyAllAccessControl(), types, warningCollector);
        for (Expression expression : expressions) {
            analyzer.analyze(expression, Scope.builder().withRelationType(RelationId.anonymous(), new RelationType()).build());
        }

        return new ExpressionAnalysis(
                analyzer.getExpressionTypes(),
                analyzer.getExpressionCoercions(),
                analyzer.getSubqueryInPredicates(),
                analyzer.getScalarSubqueries(),
                analyzer.getExistsSubqueries(),
                analyzer.getColumnReferences(),
                analyzer.getTypeOnlyCoercions(),
                analyzer.getQuantifiedComparisons(),
                analyzer.getLambdaArgumentReferences(),
                analyzer.getWindowFunctions());
    }

    public static ExpressionAnalysis analyzeExpression(
            Session session,
            Metadata metadata,
            AccessControl accessControl,
            SqlParser sqlParser,
            Scope scope,
            Analysis analysis,
            Expression expression,
            WarningCollector warningCollector)
    {
        ExpressionAnalyzer analyzer = create(analysis, session, metadata, sqlParser, accessControl, TypeProvider.empty(), warningCollector);
        analyzer.analyze(expression, scope);

        Map<NodeRef<Expression>, Type> expressionTypes = analyzer.getExpressionTypes();
        Map<NodeRef<Expression>, Type> expressionCoercions = analyzer.getExpressionCoercions();
        Set<NodeRef<Expression>> typeOnlyCoercions = analyzer.getTypeOnlyCoercions();
        Map<NodeRef<FunctionCall>, Signature> resolvedFunctions = analyzer.getResolvedFunctions();

        analysis.addTypes(expressionTypes);
        analysis.addCoercions(expressionCoercions, typeOnlyCoercions);
        analysis.addFunctionSignatures(resolvedFunctions);
        analysis.addColumnReferences(analyzer.getColumnReferences());
        analysis.addLambdaArgumentReferences(analyzer.getLambdaArgumentReferences());
        analysis.addTableColumnReferences(accessControl, session.getIdentity(), analyzer.getTableColumnReferences());

        return new ExpressionAnalysis(
                expressionTypes,
                expressionCoercions,
                analyzer.getSubqueryInPredicates(),
                analyzer.getScalarSubqueries(),
                analyzer.getExistsSubqueries(),
                analyzer.getColumnReferences(),
                analyzer.getTypeOnlyCoercions(),
                analyzer.getQuantifiedComparisons(),
                analyzer.getLambdaArgumentReferences(),
                analyzer.getWindowFunctions());
    }

    public static List<TypeSignatureProvider> getCallArgumentTypes(
            Session session,
            Metadata metadata,
            SqlParser sqlParser,
            TypeProvider types,
            List<Expression> callArguments,
            Map<NodeRef<Parameter>, Expression> parameters,
            WarningCollector warningCollector,
            boolean isDescribe)
    {
        // Expressions at this point can not have sub queries, so deny all access checks.
        // In the future, we will need a full access controller here to verify access to functions.
        Analysis analysis = new Analysis(null, parameters, isDescribe);
        ExpressionAnalyzer analyzer = create(analysis, session, metadata, sqlParser, new DenyAllAccessControl(), types, warningCollector);
        return analyzer.getCallArgumentTypes(callArguments, Scope.builder().withRelationType(RelationId.anonymous(), new RelationType()).build());
    }

    public static ExpressionAnalyzer create(
            Analysis analysis,
            Session session,
            Metadata metadata,
            SqlParser sqlParser,
            AccessControl accessControl,
            TypeProvider types,
            WarningCollector warningCollector)
    {
        return new ExpressionAnalyzer(
                metadata,
                node -> new StatementAnalyzer(analysis, metadata, sqlParser, accessControl, session, warningCollector),
                session,
                types,
                analysis.getParameters(),
                warningCollector,
                analysis.isDescribe());
    }

    public static ExpressionAnalyzer createConstantAnalyzer(Metadata metadata, Session session, Map<NodeRef<Parameter>, Expression> parameters, WarningCollector warningCollector)
    {
        return createWithoutSubqueries(
                metadata,
                session,
                parameters,
                EXPRESSION_NOT_CONSTANT,
                "Constant expression cannot contain a subquery",
                warningCollector,
                false);
    }

    public static ExpressionAnalyzer createConstantAnalyzer(Metadata metadata, Session session, Map<NodeRef<Parameter>, Expression> parameters, WarningCollector warningCollector, boolean isDescribe)
    {
        return createWithoutSubqueries(
                metadata,
                session,
                parameters,
                EXPRESSION_NOT_CONSTANT,
                "Constant expression cannot contain a subquery",
                warningCollector,
                isDescribe);
    }

    public static ExpressionAnalyzer createWithoutSubqueries(
            Metadata metadata,
            Session session,
            Map<NodeRef<Parameter>, Expression> parameters,
            ErrorCodeSupplier errorCode,
            String message,
            WarningCollector warningCollector,
            boolean isDescribe)
    {
        return createWithoutSubqueries(
                metadata,
                session,
                TypeProvider.empty(),
                parameters,
                node -> semanticException(errorCode, node, message),
                warningCollector,
                isDescribe);
    }

    public static ExpressionAnalyzer createWithoutSubqueries(
            Metadata metadata,
            Session session,
            TypeProvider symbolTypes,
            Map<NodeRef<Parameter>, Expression> parameters,
            Function<? super Node, ? extends RuntimeException> statementAnalyzerRejection,
            WarningCollector warningCollector,
            boolean isDescribe)
    {
        return new ExpressionAnalyzer(
                metadata,
                node -> {
                    throw statementAnalyzerRejection.apply(node);
                },
                session,
                symbolTypes,
                parameters,
                warningCollector,
                isDescribe);
    }
}
