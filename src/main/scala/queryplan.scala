package edu.mit.cryptdb

import net.liftweb.json._
import scala.collection.mutable.{ ArrayBuffer, HashMap }

object GlobalOpts {
  final val empty = GlobalOpts(Map.empty, Map.empty)
}

case class GlobalOpts(
  precomputed: Map[String, Map[String, SqlExpr]],
  homGroups: Map[String, Seq[Seq[SqlExpr]]]) {

  def merge(that: GlobalOpts): GlobalOpts = {

    def mergeA(lhs: Map[String, Map[String, SqlExpr]],
               rhs: Map[String, Map[String, SqlExpr]]):
      Map[String, Map[String, SqlExpr]] = {
      (lhs.keys ++ rhs.keys).map { k =>
        (k, lhs.getOrElse(k, Map.empty) ++ rhs.getOrElse(k, Map.empty))
      }.toMap
    }

    def mergeB(lhs: Map[String, Seq[Seq[SqlExpr]]],
               rhs: Map[String, Seq[Seq[SqlExpr]]]):
      Map[String, Seq[Seq[SqlExpr]]] = {
      (lhs.keys ++ rhs.keys).map { k =>
        (k, (lhs.getOrElse(k, Seq.empty).toSet ++ rhs.getOrElse(k, Seq.empty).toSet).toSeq)
      }.toMap
    }

    GlobalOpts(
      mergeA(precomputed, that.precomputed),
      mergeB(homGroups, that.homGroups))
  }

}

case class EstimateContext(
  defns: Definitions,

  globalOpts: GlobalOpts,

  // the onions required for the plan to work
  requiredOnions: Map[String, Map[String, Int]],

  // the pre-computed exprs (w/ the req onion) required for the plan to work
  precomputed: Map[String, Map[String, Int]],

  // the hom groups required for the plan to work
  // set(int) references the global opt's homGroup
  homGroups: Map[String, Set[Int]]) {

  private val _idGen = new NameGenerator("fresh_")
  @inline def uniqueId(): String = _idGen.uniqueId()

  def makeRequiredOnionSet: OnionSet = {
    val os = new OnionSet

    // add all requiredOnions
    requiredOnions.foreach { case (reln, cols) =>
      cols.foreach { case (name, onions) =>
        Onions.toSeq(onions).foreach { onion =>
          os.add(reln, FieldIdent(None, name), onion)
        }
      }
    }

    // add all precomputed
    precomputed.foreach { case (reln, exprMap) =>
      exprMap.foreach { case (name, onions) =>
        Onions.toSeq(onions).foreach { onion =>
          os.add(reln, globalOpts.precomputed(reln)(name), onion)
        }
      }
    }

    // add all homGroups
    os.withGroups(
      homGroups.map { case (reln, gids) =>
        (reln, CollectionUtils.slice(globalOpts.homGroups(reln), gids.toSeq))
      }.toMap)
  }
}

case class CodeGenContext(defns: Definitions, globalOpts: GlobalOpts)

case class Estimate(
  cost: Double,
  rows: Long,
  rowsPerRow: Long /* estimate cardinality within each aggregate group */,
  equivStmt: SelectStmt /* statement which estimates the equivalent CARDINALITY */,
  seqScanInfo: Map[String, Int] /* the number of times a seq scan is invoked, per relation */) {

  override def toString = {
    case class Estimate(c: Double, r: Long, rr: Long, ssi: Map[String, Int])
    Estimate(cost, rows, rowsPerRow, seqScanInfo).toString
  }
}

object CostConstants {

  final val PGUnitPerSec: Double = 85000.0

  final val NetworkXferBytesPerSec: Double = 10485760.0 // 10 MB/sec

  final val DiskReadBytesPerSec: Double = 104857600.0 // 100 MB/sec

//DET_ENC    = 0.0151  / 1000.0
//DET_DEC    = 0.0173  / 1000.0
//OPE_ENC    = 13.359  / 1000.0
//OPE_DEC    = 9.475   / 1000.0
//AGG_DEC    = 0.6982  / 1000.0
//AGG_ADD    = 0.00523 / 1000.0
//SWP_ENC    = 0.00373 / 1000.0
//SWP_SEARCH = 0.00352 / 1000.0

  final val AggAddSecPerOp: Double    = 0.00523 / 1000.0
  final val SwpSearchSecPerOp: Double = 0.00352 / 1000.0

  // encrypt map (cost in seconds)
  final val EncryptCostMap: Map[Int, Double] =
    Map(
      Onions.DET -> (0.0151  / 1000.0),
      Onions.OPE -> (13.359  / 1000.0),
      Onions.SWP -> (0.00373 / 1000.0))

  // decrypt map (cost in seconds)
  final val DecryptCostMap: Map[Int, Double] =
    Map(
      Onions.DET -> (0.0173  / 1000.0),
      Onions.OPE -> (9.475   / 1000.0),
      Onions.HOM -> (0.6982  / 1000.0))

  @inline def secToPGUnit(s: Double): Double = {
    s * PGUnitPerSec
  }

  final val GroupByRowsRate: Double = 1.0/1.79758e6 // secs/row
}

case class PosDesc(
  origTpe: DataType,
  origFieldPos: Option[Int],
  onion: OnionType,
  partOfPK: Boolean,
  vectorCtx: Boolean) {

  // TODO: fix this hack- right now we go ahead and
  // encrypt all dets of primary keys with detjoins
  val join = partOfPK && onion == RegularOnion(Onions.DET)

  def toCPP: String = {
    "db_column_desc(%s, %d, %s, %s, %d, %b)".format(
      origTpe.toCPP,
      origTpe.size,
      onion.toCPP,
      onion.seclevelToCPP(join),
      origFieldPos.getOrElse(0),
      vectorCtx)
  }

  def encryptDesc(newOnion: OnionType): PosDesc =
    origTpe match {
      case DoubleType =>
        // XXX: Hack for TPC-H
        // double type encrypts into DECIMAL(15, 2) for now
        copy(origTpe = DecimalType(15, 2), onion = newOnion)
      case _ => copy(onion = newOnion)
    }
}

case class UserAggDesc(
  rows: Long,
  rowsPerRow: Option[Long],
  selectivityMap: Map[String, Long])

trait PgQueryPlanExtractor {

  // the 4th return value is a map of
  //
  def extractCostFromDBStmt(stmt: SelectStmt, dbconn: DbConn,
                            stats: Option[Statistics] = None):
    (Double, Long, Option[Long],
     Map[String, UserAggDesc], Map[String, Int]) = {
    extractCostFromDBSql(stmt.sqlFromDialect(PostgresDialect), dbconn, stats)
  }

  def extractCostFromDBSql(sql: String, dbconn: DbConn,
                           stats: Option[Statistics] = None):
    (Double, Long, Option[Long],
     Map[String, UserAggDesc], Map[String, Int]) = {
    // taken from:
    // http://stackoverflow.com/questions/4170949/how-to-parse-json-in-scala-using-standard-scala-classes
    class CC[T] {
      def unapply(a: Any): Option[T] = Some(a.asInstanceOf[T])
    }
    object M extends CC[Map[String, Any]]
    object L extends CC[List[Any]]

    object S extends CC[String]
    object D extends CC[Double]
    object B extends CC[Boolean]

    type ExtractInfo =
      (Double, Long, Option[Long],
       Map[String, UserAggDesc], Map[String, Long],
       Map[String, Int])

    def extractInfoFromQueryPlan(node: Map[String, Any]): ExtractInfo = {
      val childrenNodes =
        for (L(children) <- node.get("Plans").toList; M(child) <- children)
        yield (child("Parent Relationship").asInstanceOf[String], extractInfoFromQueryPlan(child))

      val outerChildren = childrenNodes.filter(_._1 == "Outer").map(_._2)
      val innerOuterChildren = childrenNodes.filter(x => x._1 == "Outer" || x._1 == "Inner").map(_._2)

      val Some((totalCost, planRows)) = for (
        D(totalCost) <- node.get("Total Cost");
        D(planRows)  <- node.get("Plan Rows")
      ) yield ((totalCost, planRows.toLong))

      def noOverwriteFoldLeft[K, V](ss: Seq[Map[K, V]]): Map[K, V] = {
        ss.foldLeft( Map.empty : Map[K, V] ) {
          case (acc, m) =>
            acc ++ m.flatMap { case (k, v) =>
              if (!acc.contains(k)) Some((k, v)) else None
            }.toMap
        }
      }

      def mergePlus(lhs: Map[String, Int], rhs: Map[String, Int]): Map[String, Int] = {
        (lhs.keys ++ rhs.keys).map { k =>
          (k, lhs.getOrElse(k, 0) + rhs.getOrElse(k, 0))
        }.toMap
      }

      val userAggMapFold = noOverwriteFoldLeft(childrenNodes.map(_._2).map(_._4).toSeq)
      val selMapFold = noOverwriteFoldLeft(innerOuterChildren.map(_._5).toSeq)
      val seqScanInfoFold = childrenNodes.foldLeft(Map.empty : Map[String, Int]) {
        case (acc, est) => mergePlus(acc, est._2._6)
      }

      // see if it has a hom_agg aggregate
      // TODO: this is quite rigid.. we should REALLY be using our
      // SQL parser to parse this expr, except it doesn't support the
      // postgres extension ::cast syntax...
      val HomAggRegex = "hom_agg\\(.*, '[a-zA-Z_]+'::character varying, \\d+, '([a-zA-Z0-9_]+)'::character varying\\)".r

      // see if we have searchswp udf
      // once again, this is fragile, use SQL parser in future
      val SearchSWPRegex =
        "searchswp\\([a-zA-Z0-9_.]+, '[^']*'::character varying, NULL::character varying, '([a-zA-Z0-9_]+)'::character varying\\)".r

      val tpe = node("Node Type")
      tpe match {
        case "Aggregate" =>
          // must have outer child
          assert(!outerChildren.isEmpty)

          // compute the values that the aggs will have to experience, based
          // on the first outer child
          val (rOuter, rrOuter) =
            outerChildren.head match { case (_, r, rr, _, _, _) => (r, rr) }

          val aggs =
            (for (L(fields) <- node.get("Output").toList;
                  S(expr)   <- fields) yield {
              expr match {
                case HomAggRegex(id) =>
                  Seq((id, UserAggDesc(rOuter, rrOuter, selMapFold)))
                case _ =>
                  SearchSWPRegex.findAllIn(expr).matchData.map { m =>
                    val id = m.group(1)
                    ((id, UserAggDesc(rOuter, rrOuter, selMapFold)))
                  }
              }
            }).flatten.toMap

          // compute the values of THIS node based on the first outer child
          val (r, rr) = outerChildren.head match {
            case (_, rows, None, _, _, _) =>
               (planRows, Some(math.ceil(rows.toDouble / planRows.toDouble).toLong))

            case (_, rows, Some(rowsPerRow), _, _, _) =>
               (planRows, Some(math.ceil(rows.toDouble / planRows.toDouble * rowsPerRow).toLong))
          }

          (totalCost, r, rr,
           noOverwriteFoldLeft(Seq(userAggMapFold, aggs)),
           selMapFold, seqScanInfoFold)

        case "Hash Join" /* XXX: other types of joins */ =>
          val rrOuter = outerChildren.headOption.flatMap(_._3)

          val aggs =
            (for (L(fields) <- node.get("Output").toList;
                  S(expr)   <- fields) yield {
              expr match {
                case HomAggRegex(id) =>
                  Seq((id, UserAggDesc(planRows, rrOuter, selMapFold)))
                case _ =>
                  SearchSWPRegex.findAllIn(expr).matchData.map { m =>
                    val id = m.group(1)
                    ((id, UserAggDesc(planRows, rrOuter, selMapFold)))
                  }
              }
            }).flatten.toMap

          (totalCost, planRows, rrOuter,
           noOverwriteFoldLeft(Seq(userAggMapFold, aggs)),
           selMapFold, seqScanInfoFold)

        case "Seq Scan" | "Index Scan" =>
          assert(innerOuterChildren.isEmpty) // seq scans don't have children?

          val S(reln) = node("Relation Name")

          val filterName = if (tpe == "Seq Scan") "Filter" else "Index Cond"

          val m = node.get("Filter").map { case S(f) =>
            SearchSWPRegex.findAllIn(f).matchData.map { m =>
              // need to estimate r from the relation
              val r = stats.map(_.stats(reln).row_count).getOrElse(
                extractCostFromDBSql("SELECT * FROM %s".format(reln), dbconn, None)._2)
              val id = m.group(1)
              ((id, UserAggDesc(r, None, Map.empty)))
            }.toMap
          }.getOrElse(Map.empty)

          val (r, rr) = (planRows, None)

          // exclude temp tables from SSI costs
          // TODO: this is hacky
          val ssiMapRhs =
            if (reln.startsWith(RemoteSql.globalUniqueNameGenerator.prefix)) {
              (Map.empty : Map[String, Int])
            } else Map(reln -> 1)

          (totalCost, r, rr,
           noOverwriteFoldLeft(Seq(userAggMapFold, m)),
           Map(reln -> planRows), mergePlus(seqScanInfoFold, ssiMapRhs))

        case t =>
          // simple case, just read from this node only
          (totalCost, planRows, outerChildren.headOption.flatMap(_._3),
           userAggMapFold, selMapFold, seqScanInfoFold)
      }
    }

    import Conversions._

    // TODO: do a better job not leaking the stmt object
    val stmt = dbconn.getConn.createStatement
    val r =
      try {
        //println("sending query to psql:")
        //println(sql)
        stmt.executeQuery("EXPLAIN (VERBOSE, FORMAT JSON) " + sql)
      } catch {
        case e =>
          println("bad sql:")
          println(sql)
          stmt.close()
          throw e
      }
    val res = r.map { rs =>
      val jsonAST = parse(rs.getString(1)) transform {
        case JInt(x) => JDouble(x.toDouble)
      }
      val planJson = Some(jsonAST.values.asInstanceOf[Any])
      (for (L(l) <- planJson;
            M(m) = l.head;
            M(p) <- m.get("Plan")) yield extractInfoFromQueryPlan(p)).getOrElse(
        throw new RuntimeException("unexpected return from postgres: " + planJson)
      )
    }
    stmt.close()

    (res.head._1, res.head._2, res.head._3, res.head._4, res.head._6)
  }
}

trait PlanNode extends Traversals with Transformers with Resolver with PgQueryPlanExtractor {
  // actual useful stuff

  def tupleDesc: Seq[PosDesc]

  def underlying: Option[PlanNode]

  protected def costEstimateImpl(ctx: EstimateContext, stats: Statistics): Estimate
  protected var _lastCostEstimate : Option[Estimate] = None

  def costEstimate(ctx: EstimateContext, stats: Statistics): Estimate = {
    val ret = costEstimateImpl(ctx, stats)
    _lastCostEstimate = Some(ret)
    ret
  }

  protected def resolveCheck(stmt: SelectStmt, defns: Definitions, force: Boolean = false):
    SelectStmt = {
    // this is used to debug whether or not our sql statements sent
    // to the DB for cardinality estimation are valid or not

    try {
      resolve(stmt, defns)
    } catch {
      case e =>
        System.err.println("Bad sql stmt: " + stmt.sql)
        System.err.println("Offending Node:")
        System.err.println(pretty)
        System.err.println("Defns: " + defns.defns)
        throw e
    }
  }

  def emitCPPHelpers(cg: CodeGenerator, ctx: CodeGenContext): Unit

  def emitCPP(cg: CodeGenerator, ctx: CodeGenContext): Unit

  // printing stuff
  def pretty: String = pretty0(0)

  protected def pretty0(lvl: Int): String
  protected def childPretty(lvl: Int, child: PlanNode): String =
    endl + indent(lvl + 1) + child.pretty0(lvl + 1)

  protected def indent(lvl: Int) = " " * (lvl * 4)
  protected def endl: String = "\n"
}

// a temporary hack that should not need to exist in
// a properly designed system
object FieldNameHelpers {
  def basename(s: String): String = {
    if (s.contains("$")) s.split("\\$").dropRight(1).mkString("$")
    else s
  }

  // given a field name formatted name$ONION, returns the onion type
  // (as an int)
  def encType(s: String): Option[Int] = {
    if (!s.contains("$")) None
    else Onions.onionFromStr(s.split("\\$").last)
  }
}

object RemoteSql {
  // TODO: this is a hack- we should really cleanup the temp tables
  val globalUniqueNameGenerator = new ThreadSafeNameGenerator("_fresh")

  private val _defnsCache =
    new java.util.concurrent.ConcurrentHashMap[String, Definitions]

  def makeIntTemporaryTable(nRows: Long, dbconn: DbConn): (Definitions, String) = {
    val (created, tempTableName) =
      dbconn.makeIntTemporaryTable(
        nRows, RemoteSql.globalUniqueNameGenerator.uniqueId())

    //println("created=(%b), tempTableName=(%s)".format(created, tempTableName))

    var d = _defnsCache.get(tempTableName)
    if (d eq null) {
      val schema = new PgDbConnSchema(dbconn.asInstanceOf[PgDbConn])
      d = schema.loadSchema()
      _defnsCache.putIfAbsent(tempTableName, d)
    }
    (d, tempTableName)
  }

  lazy val UserTranslator: user.Translator = {
    Option(System.getProperty("userTranslatorClass")).flatMap { clsName =>
      try {
        Some(Class.forName(clsName).newInstance.asInstanceOf[user.Translator])
      } catch {
        case e =>
          Console.err.println("[WARN] could not instantiate user translator: %s".format(clsName))
          Console.err.println("[WARN] message: %s".format(e.getMessage))
        None
      }
    }.getOrElse(new tpch.TPCHTranslator)
  }
}

case class LocalFlattener(child: PlanNode)
  extends PlanNode {

  def tupleDesc = {
    val td = child.tupleDesc
    if (!td.filter(_.vectorCtx).isEmpty) {
      println("child: " + child)
    }
    td.foreach(x => assert(!x.vectorCtx))
    td.map(_.copy(vectorCtx = true))
  }

  def underlying = Some(child)

  protected def costEstimateImpl(ctx: EstimateContext, stats: Statistics) =
    child.costEstimate(ctx, stats)

  def emitCPPHelpers(cg: CodeGenerator, ctx: CodeGenContext) =
    child.emitCPPHelpers(cg, ctx)

  def emitCPP(cg: CodeGenerator, ctx: CodeGenContext) = {
    cg.blockBegin("new local_flattener_op(")
      child.emitCPP(cg, ctx)
    cg.blockEnd(")")
  }

  protected def pretty0(lvl: Int) =
    "* LocalFlattener()" + childPretty(lvl, child)
}

case class RemoteSql(stmt: SelectStmt,
                     projs: Seq[PosDesc],

                     /* These stmts have side-effect of causing a temp table on the
                      * server to be loaded */
                     subrelations: Seq[(RemoteMaterialize, SelectStmt)] = Seq.empty,

                     /* These stmts will be evaluated by the query to substitute values.
                      * these subselects do *not* accept arguments, so they can be safely
                      * run at the beginning of the query */
                     namedSubselects: Map[String, (PlanNode, SelectStmt)] = Map.empty)

  extends PlanNode with Transformers with PrettyPrinters with Timer {

  def underlying = None

  assert(stmt.projections.size == projs.size)
  def tupleDesc = projs
  def pretty0(lvl: Int) = {
    "* RemoteSql(cost = " + _lastCostEstimate + ", sql = " + stmt.sql + ", projs = " + projs + ")" +
    subrelations.map(c => childPretty(lvl, c._1)).mkString("") +
    namedSubselects.map(c => childPretty(lvl, c._2._1)).mkString("")
  }

  def costEstimateImpl(ctx: EstimateContext, stats: Statistics) = {
    // TODO: this is very hacky, and definitely prone to error
    // but it's a lot of mindless work to propagate the precise
    // node replacement information, so we just use some text
    // substitution for now, and wait to implement a real solution

    import FieldNameHelpers._

    def rewriteWithQual[N <: Node](n: N, q: String): N =
      topDownTransformation(n) {
        case f: FieldIdent => (Some(f.copy(qualifier = Some(q))), false)
        case _ => (None, true)
      }.asInstanceOf[N]

    var useSchema: Option[Definitions] = None

    def proc[N <: Node](n: Node): N = {
      topDownTransformation(n) {
        case a @ AggCall("hom_agg", args, _) =>
          // need to assign a unique ID to this hom agg
          (Some(a.copy(args = args ++ Seq(StringLiteral(ctx.uniqueId())))), true)

        case s @ FunctionCall("searchSWP", args, _) =>
          // need to assign a unique ID to this UDF
          (Some(s.copy(args = args ++ Seq(NullLiteral(), StringLiteral(ctx.uniqueId())))), true)

        case FieldIdent(Some(qual), name, _, _) =>
          // check precomputed first
          val qual0 = basename(qual)
          val name0 = basename(name)
          ctx.globalOpts.precomputed.get(qual0).flatMap(_.get(name0))
            .map(x => (Some(rewriteWithQual(x, qual0)), false))
            .getOrElse {
              // rowids are rewritten to 0
              if (ctx.homGroups.contains(qual0) && name0 == "row_id") {
                ((Some(IntLiteral(0)), false))
              } else if (qual != qual0 || name != name0) {
                ((Some(FieldIdent(Some(qual0), name0)), false))
              } else ((None, false))
            }

        case TableRelationAST(name, alias, _) =>
          val SRegex = "subrelation\\$(\\d+)".r
          name match {
            case SRegex(srpos) =>
              // need to replace with SubqueryRelationAST
              (Some(SubqueryRelationAST(subrelations(srpos.toInt)._2, alias.get)), false)
            case _ =>
              val name0 = basename(name)
              if (ctx.defns.tableExists(name0)) {
                (Some(TableRelationAST(name0, alias)), false)
              } else {
                (None, false)
              }
          }

        case In(e, Seq(NamedSubselectPlaceholder(name, _)), n, _) =>
          val ch = namedSubselects(name)._1.costEstimate(ctx, stats)
          //println("IN clause for namedSubselect=(%s) contains (%d) elements".format(name, ch.rows.toInt))
          if (ch.rows.toInt > 10000) {
            // if we have too many elements for the IN clause, then we just stash the
            // values into a temp table
            // TODO: don't always assume that value is a bunch of ints
            val (schema, tempTableName) =
              RemoteSql.makeIntTemporaryTable(10000, ctx.defns.dbconn.get)

            useSchema = Some(schema)

            (Some(In(e, Seq(Subselect(
              SelectStmt(
                Seq(ExprProj(FieldIdent(None, "col0"), None)),
                Some(Seq(TableRelationAST(tempTableName, None))),
                None,
                None,
                None,
                None))), false)), true)
          } else {
            (Some(In(e, (1 to ch.rows.toInt).map(x => IntLiteral(x * 2)).toSeq, n)), true)
          }

        case NamedSubselectPlaceholder(name, _) =>
          // TODO: we should generate something smarter than this
          // - at least the # should be a reasonable number for what it is
          // - replacing
          // TODO: we should also generate the right TYPE of literal placeholder
          (Some(IntLiteral(12345)), false)

        case FunctionCall("encrypt", Seq(e, _, _), _) =>
          (Some(e), false)

        case _: BoundDependentFieldPlaceholder =>
          // TODO: we should generate something smarter than this
          // - at least the # should be a reasonable number for what it is
          // - replacing
          // TODO: we should also generate the right TYPE of literal placeholder
          (Some(IntLiteral(12345)), false)

        case FunctionCall("hom_row_desc_lit", Seq(e), _) =>
          (Some(e), false)

        case gc : GroupConcat =>
          // don't pass hexify to the plain text query
          (Some(gc.copy(hexify = false)), true)

        case AggCall("group_serializer", Seq(e), _) =>
          (Some(GroupConcat(e, ",")), true)

        case AggCall("agg_ident", Seq(e), _) =>
          // no need to pass agg_ident
          (Some(proc[SqlExpr](e)), true)

        case _ => (None, true)
      }.asInstanceOf[N]
    }

    val reverseStmt0 =
      resolveCheck(proc[SelectStmt](stmt), useSchema.getOrElse(ctx.defns))

    // server query execution cost
    val (c, r, rr, m, ssi) =
      extractCostFromDBStmt(reverseStmt0, ctx.defns.dbconn.get, Some(stats))

    //println("sql: " + reverseStmt0.sqlFromDialect(PostgresDialect))
    //println("m: " + m)

    var aggCost: Double = 0.0

    def topDownTraverseCtx(stmt: SelectStmt): Unit = {
      topDownTraversal(stmt) {

        case f @ FunctionCall("searchSWP", args, _) =>
          val Seq(_, _, _, StringLiteral(id, _)) = args
          aggCost += m(id).rows * CostConstants.secToPGUnit(CostConstants.SwpSearchSecPerOp)
          false

        case a @ AggCall("hom_agg", args, sqlCtx) =>
          val Seq(_, StringLiteral(tbl, _), IntLiteral(grp, _), StringLiteral(id, _)) = args
          assert(sqlCtx == stmt.ctx)

          // compute correlation score
          val corrScore =
            stmt.groupBy.map { case SqlGroupBy(keys, _, _) =>
              // multiply the correlation scores of each key
              //
              // if expr is not a key but rather an expression,
              // just assume key correlation score of 0.33
              //
              // the meaning of the correlation number is given in:
              // http://www.postgresql.org/docs/9.1/static/view-pg-stats.html
              //
              // since the value given is between +/- 1.0, we simply take the
              // abs value

              def followProjs(e: SqlExpr): SqlExpr =
                e match {
                  case FieldIdent(_, _, ProjectionSymbol(name, ctx, _), _) =>
                    val expr1 = ctx.lookupProjection(name).get
                    followProjs(expr1)
                  case _ => e
                }

              val DefaultGuess = 0.333

              keys.foldLeft(1.0) {
                case (acc, expr) =>
                  val corr = followProjs(expr) match {
                    case FieldIdent(_, _, ColumnSymbol(reln, col, _, _), _) =>
                      stats.stats.get(reln)
                        .flatMap(_.column_stats.get(col).map(x => math.abs(x.correlation)))
                        .getOrElse(DefaultGuess)
                    case _ => DefaultGuess // see above note
                  }
                  acc * corr
              }
            }.getOrElse(1.0)

          // find selectivity of the table. compute as a fraction of the
          // # of rows of the table
          val selScore =
            m(id).selectivityMap.get(tbl).map { rows =>
              val sel = rows.toDouble / stats.stats(tbl).row_count.toDouble
              assert(sel >= 0.0)
              assert(sel <= 1.0)
              sel
            }.getOrElse {
              println("Could not compute selectivity info for table %s, assuming 0.75".format(tbl))
              0.75
            }

          // totalScore
          val totalScore = corrScore * selScore
          assert(totalScore >= 0.0)
          assert(totalScore <= 1.0)

          // TODO: need to take into account ciphertext size
          val costPerAgg = CostConstants.secToPGUnit(CostConstants.AggAddSecPerOp)

          val homGroup = ctx.globalOpts.homGroups(tbl)(grp.toInt)
          // assume each group right now take 83 bits of ciphertext space

          val sizePerGroupElem = 83 // assumes that we do perfect packing (size in PT)

          val minAggPTSize = 1024 // bits in PT

          val fileSize = // bytes
            math.max(
              (stats.stats(tbl).row_count * homGroup.size * sizePerGroupElem * 2).toDouble / 8.0,
              minAggPTSize.toDouble * 2.0 / 8.0 /* min size (kind of a degenerate case */)

          // for now, assume that we only have to read proportional to the selectivity factor
          // of the table (this is optimistic)
          val readTime = (fileSize * selScore) / CostConstants.DiskReadBytesPerSec

          val readCost = CostConstants.secToPGUnit(readTime)

          val packingFactor =
            math.max(
              math.ceil( minAggPTSize.toDouble / (sizePerGroupElem * homGroup.size).toDouble ),
              1.0)

          assert(packingFactor >= 1.0)

          // min cost of agg is perfect packing
          val minCost =
            costPerAgg * m(id).rows.toDouble * m(id).rowsPerRow.getOrElse(1L).toDouble / packingFactor

          // max cost of agg is eval all rows separately
          val maxCost =
            costPerAgg * m(id).rows.toDouble * m(id).rowsPerRow.getOrElse(1L).toDouble

          // use totalScore to scale us between min/max cost
          val scaledCost = math.min(minCost / totalScore, maxCost)

          //println("call " + a.sql + " stats: " + m(id))

          // add to total cost
          aggCost += (readCost + scaledCost)

          false
        case SubqueryRelationAST(ss, _, _) =>
          topDownTraverseCtx(ss)
          false
        case Subselect(ss, _) =>
          topDownTraverseCtx(ss)
          false
        case e =>
          assert(e.ctx == stmt.ctx)
          true
      }
    }

    topDownTraverseCtx(reverseStmt0)

    // adjust sequential scans to reflect extra onions
    // TODO: we need to take onion sizes into account- for now, let's
    // just assume all onions are the same size. So the cost we get
    // from the DB includes 1 onion per column. We scan through the
    // required+precomputed onions and any columns which require > 1
    // onion get an additional cost tacked on to their sequential scans
    var addSeqScanCost = 0.0
    ssi.foreach { case (reln, nscans) =>
      def proc(m: Map[String, Int]) = {
        m.foreach { case (_, os) =>
          val oseq = Onions.toSeq(os)
          assert(oseq.size >= 1)
          if (oseq.size > 1) {
            val n = oseq.size - 1
            // TODO: don't treat all onions the same size
            addSeqScanCost += CostConstants.secToPGUnit(
              4.0 * n.toDouble *
              stats.stats(reln).row_count.toDouble / CostConstants.DiskReadBytesPerSec *
              nscans.toDouble)
          }
        }
      }
      proc(ctx.requiredOnions.getOrElse(reln, Map.empty))
      proc(ctx.precomputed.getOrElse(reln, Map.empty))
    }

    // data xfer to client cost
    val td = tupleDesc
    val bytesToXfer = td.map {
      case PosDesc(_, _, onion, _, vecCtx) =>
        // assume everything is 4 bytes now
        if (vecCtx) 4.0 * rr.get else 4.0
    }.sum * r.toDouble

    // subrelations
    val srCosts = subrelations.map(_._1.costEstimate(ctx, stats))
    val nsCosts = namedSubselects.map(_._2._1.costEstimate(ctx, stats))

    def mergePlus(lhs: Map[String, Int], rhs: Map[String, Int]): Map[String, Int] = {
      (lhs.keys ++ rhs.keys).map { k =>
        (k, lhs.getOrElse(k, 0) + rhs.getOrElse(k, 0))
      }.toMap
    }

    val ssiMerged =
      (srCosts.map(_.seqScanInfo) ++ nsCosts.map(_.seqScanInfo) ++ Seq(ssi))
        .foldLeft( Map.empty : Map[String, Int] )(mergePlus)

    Estimate(
      c + aggCost + addSeqScanCost +
        srCosts.map(_.cost).sum +
        nsCosts.map(_.cost).sum +
        CostConstants.secToPGUnit(bytesToXfer / CostConstants.NetworkXferBytesPerSec) +
        CostConstants.secToPGUnit( 0.001 /* 1ms latency to contact server */ ),
      r, rr.getOrElse(1), reverseStmt0, ssiMerged)
  }

  private var _paramClassName: String = null
  private var _paramStmt: SelectStmt = null

  def emitCPPHelpers(cg: CodeGenerator, ctx: CodeGenContext) = {
    subrelations.foreach(_._1.emitCPPHelpers(cg, ctx))
    namedSubselects.foreach(_._2._1.emitCPPHelpers(cg, ctx))

    //assert(_paramClassName eq null)
    //assert(_paramStmt eq null)

    _paramClassName = "param_generator_%s".format(cg.uniqueId())
    cg.println("class %s : public sql_param_generator {".format(_paramClassName))
    cg.println("public:")
    cg.blockBegin("virtual param_map get_param_map(exec_context& ctx) {")

      cg.println("param_map m;")

      var i = 0
      def nextId(): Int = {
        val ret = i
        i += 1
        ret
      }

      def subMarkers(q: String): String = q.replaceAll("\\$", "_")

      import FieldNameHelpers._

      _paramStmt = topDownTransformation(stmt) {
        case FieldIdent(qual, name, _, _) =>
          val qual0 = basename(qual.get)
          val name0 = basename(name)
          val qualName = RemoteSql.UserTranslator.translateTableName(qual0)
          // check if precomputed using globalOpts. if so,
          // ask the translator to rewrite the name
          ctx.globalOpts.precomputed.get(qual0).flatMap(_.get(name0).map { expr =>
            val newName = RemoteSql.UserTranslator.translatePrecomputedExprName(
              name0, qual0, expr, encType(name).get)
            (Some(FieldIdent(Some(qualName), newName)), false)
          }).getOrElse {
            // default case
            (Some(FieldIdent(Some(qualName), subMarkers(name))), false)
          }
        case TableRelationAST(name, alias, _) =>
          val name0 = basename(name)
          val qualName = RemoteSql.UserTranslator.translateTableName(name0)
          (Some(TableRelationAST(qualName, alias)), false)

        case BoundDependentFieldPlaceholder(pos, onion, _) =>
          val id = nextId()
          // right now, we just assume the passed in argument is already the correct onion
          // TODO: fix this assumption
          cg.println("m[%d] = ctx.args->columns.at(%d);".format(id, pos))
          (Some(QueryParamPlaceholder(id)), false)

        case AggCall("hom_agg", Seq(f0, StringLiteral(reln, _), IntLiteral(gid, _)), _) =>
          // need args of format:

          // public_key (bytea),
          // filename (varchar),
          // agg_size (int64),
          // rows_per_agg (int64),
          // row_id (int64),

          val group = ctx.globalOpts.homGroups(reln)(gid.toInt)

          val filename = RemoteSql.UserTranslator.filenameForHomAggGroup(
            gid.toInt, ctx.defns.dbconn.get.db, reln, group)

          val plainSizeBytes = RemoteSql.UserTranslator.sizeInfoForHomAggGroup(
            reln, group)

          val plainSizeBits = plainSizeBytes * 8

          val nBitsPerAgg = 83 // TODO: this is hardcoded in our system in various places

          val nBitsPerRow = nBitsPerAgg * group.size

          val rowsPerAgg = math.floor( plainSizeBits.toDouble / nBitsPerRow.toDouble ).toInt

          val id = nextId()
          cg.blockBegin("{")
            cg.println("static const size_t RowColPackPlainSize = %d;".format(plainSizeBits))
            cg.println("static const size_t RowColPackCipherSize = RowColPackPlainSize * 2;")
            cg.println("auto pp = ctx.pp_cache->get_paillier_priv(RowColPackCipherSize / 2, RowColPackCipherSize / 8);")
            cg.println("auto pk = pp.pubkey();")
            cg.println("m[%d] = (RowColPackCipherSize == 2048) ? db_elem(ctx.crypto->cm->getPKInfo()) : db_elem(StringFromZZ(pk[0] * pk[0]));".format(id))
          cg.blockEnd("}")

          // XXX: need to read optimizer config to see if we allow multi-slot
          // optimizations for now, assume we do
          (Some(AggCall("agg_hash", Seq(QueryParamPlaceholder(id), StringLiteral(filename), IntLiteral(plainSizeBits * 2 / 8), IntLiteral(rowsPerAgg), StringLiteral("t"), f0))), true)

        case FunctionCall("encrypt", Seq(e, IntLiteral(o, _), MetaFieldIdent(pos, tpe, _)), _) =>
          assert(e.isLiteral)
          assert(BitUtils.onlyOne(o.toInt))

          // constant fold the expr before encrypting it
          val e0 = e.evalLiteral.map(_.toAST).getOrElse(e)

          // TODO: figure out if we need join (assume we don't for now)
          val id = nextId()
          cg.println(
            "m[%d] = %s;".format(
              id, e0.toCPPEncrypt(o.toInt, false, pos, tpe)))

          (Some(QueryParamPlaceholder(id)), false)

        case FunctionCall("searchSWP", Seq(expr, pattern), _) =>
          val FunctionCall("encrypt", Seq(p, _, MetaFieldIdent(pos, tpe, _)), _) = pattern

          // assert pattern is something we can handle...
          val p0 = p.asInstanceOf[StringLiteral].v
          CollectionUtils.allIndicesOf(p0, '%').foreach { i =>
            assert(i == 0 || i == (p0.size - 1))
          }

          // split on %
          val tokens = p0.split("%").filterNot(_.isEmpty)
          assert(!tokens.isEmpty)
          if (tokens.size > 1) {
            throw new RuntimeException("cannot handle multiple tokens for now")
          }

          val id0 = nextId()
          val id1 = nextId()

          // generate code to encrypt the search token, and replace searchSWP w/ the params
          cg.blockBegin("{")

            cg.println(
              "Binary key(ctx.crypto->cm->getKey(ctx.crypto->cm->getmkey(), fieldname(_FP(%d), \"SWP\"), SECLEVEL::SWP));".format(pos))
            cg.println(
              "Token t = CryptoManager::token(key, Binary(%s));".format(
                quoteDbl(tokens.head.toLowerCase)))

            cg.println(
              "m[%d] = db_elem((const char *)t.ciph.content, t.ciph.len);".format(id0))
            cg.println(
              "m[%d] = db_elem((const char *)t.wordKey.content, t.wordKey.len);".format(id1))

          cg.blockEnd("}")

          (Some(
            FunctionCall(
              "searchSWP", Seq(QueryParamPlaceholder(id0), QueryParamPlaceholder(id1), expr))), true)

        case _ => (None, true)
      }.asInstanceOf[SelectStmt]

      cg.println("return m;")

    cg.blockEnd("}")
    cg.println("};")
  }

  def emitCPP(cg: CodeGenerator, ctx: CodeGenContext) = {
    assert(_paramClassName ne null)
    assert(_paramStmt ne null)

    cg.print("new remote_sql_op(new %s, ".format(_paramClassName))
    cg.printStr(_paramStmt.sqlFromDialect(PostgresDialect))
    cg.print(", %s".format( projs.map(_.toCPP).mkString("{", ", ", "}") ))
    cg.print(", {")

    CollectionUtils
      .foreachWithAllButLastAction(subrelations)(_._1.emitCPP(cg, ctx))(() => cg.println(", "))

    cg.print("}, util::map_from_pair_vec<std::string, physical_operator*>({")

    CollectionUtils.foreachWithAllButLastAction(namedSubselects.toSeq)({
      case (name, (plan, _)) =>
        cg.print("std::pair<std::string, physical_operator*>(%s, ".format(quoteDbl(name)))
        plan.emitCPP(cg, ctx)
        cg.print(")")
    })(() => cg.println(", "))

    cg.print("}))")
  }
}

case class RemoteMaterialize(name: String, child: PlanNode) extends PlanNode {
  def underlying = Some(child)

  def tupleDesc = child.tupleDesc
  def pretty0(lvl: Int) =
    "* RemoteMaterialize(cost = " + _lastCostEstimate + ", name = " + name + ")" + childPretty(lvl, child)

  def costEstimateImpl(ctx: EstimateContext, stats: Statistics) = {
    val ch = child.costEstimate(ctx, stats)

    // compute cost of xfering each row back to server
    val td = tupleDesc
    val bytesToXfer = td.map {
      case PosDesc(_, _, onion, _, vecCtx) =>
        // assume everything is 4 bytes now
        if (vecCtx) 4.0 * ch.rowsPerRow else 4.0
    }.sum * ch.rows.toDouble
    val xferCost = CostConstants.secToPGUnit(bytesToXfer / CostConstants.NetworkXferBytesPerSec)

    ch.copy(cost = ch.cost + xferCost)
  }

  def emitCPPHelpers(cg: CodeGenerator, ctx: CodeGenContext) = child.emitCPPHelpers(cg, ctx)

  def emitCPP(cg: CodeGenerator, ctx: CodeGenContext) = throw new RuntimeException("TODO")
}

case class LocalOuterJoinFilter(
  expr: SqlExpr, origRelation: SqlRelation, posToNull: Seq[Int],
  child: PlanNode, subqueries: Seq[PlanNode]) extends PlanNode {

  {
    val td = child.tupleDesc
    def checkBounds(i: Int) = assert(i >= 0 && i < td.size)
    posToNull.foreach(checkBounds)
  }

  def underlying = Some(child)

  def tupleDesc = child.tupleDesc
  def pretty0(lvl: Int) = {
    "* LocalOuterJoinFilter(cost = " + _lastCostEstimate + ", filter = " + expr.sql +
      ", posToNull = " + posToNull + ")" +
      childPretty(lvl, child) +
      subqueries.map(c => childPretty(lvl, c)).mkString("")
  }

  def costEstimateImpl(ctx: EstimateContext, stats: Statistics) = {
    val ch = child.costEstimate(ctx, stats)

    // we need to find a way to map the original relation given to the modified
    // relations given in the equivStmt.  we're currently using a heuristic
    // now, looking at join patterns for equivalences. this probably doesn't
    // capture all the cases, but since this operator is rarely needed for
    // TPC-H, we don't bother optimizing this for now

    sealed abstract trait JoinMode
    case class PrimitiveJT(name: String, alias: Option[String]) extends JoinMode
    case class MultiJT(left: JoinMode, right: JoinMode, tpe: JoinType) extends JoinMode

    def relationToJoinMode(r: SqlRelation): JoinMode =
      r match {
        case TableRelationAST(n, a, _) => PrimitiveJT(n, a)
        case JoinRelation(l, r, t, _, _) =>
          MultiJT(relationToJoinMode(l),
                  relationToJoinMode(r),
                  t)
        case _ => throw new RuntimeException("TODO: cannot handle now: " + r)
      }

    val origJoinMode = relationToJoinMode(origRelation)

    val stmt =
      resolveCheck(
        topDownTransformation(ch.equivStmt) {
          case r: SqlRelation if (relationToJoinMode(r) == origJoinMode) =>
            (Some(origRelation), false)
          case _ => (None, true)
        }.asInstanceOf[SelectStmt],
        ch.equivStmt.ctx.defns)

    val (_, r, rr, _, _) = extractCostFromDBStmt(stmt, ctx.defns.dbconn.get, Some(stats))

    // TODO: estimate the cost
    Estimate(ch.cost, r, rr.getOrElse(1), stmt, ch.seqScanInfo)
  }

  def emitCPPHelpers(cg: CodeGenerator, ctx: CodeGenContext) =
    (Seq(child) ++ subqueries).foreach(_.emitCPPHelpers(cg, ctx))

  def emitCPP(cg: CodeGenerator, ctx: CodeGenContext) = throw new RuntimeException("TODO")
}

// the canPushDownFilter flag is really a nasty hack
case class LocalFilter(expr: SqlExpr, origExpr: SqlExpr,
                       child: PlanNode, subqueries: Seq[PlanNode],
                       canPushDownFilter: Boolean = true) extends PlanNode {
  def underlying = Some(child)
  def tupleDesc = child.tupleDesc
  def pretty0(lvl: Int) = {
    "* LocalFilter(cost = " + _lastCostEstimate + ", filter = " + expr.sql + ")" +
      childPretty(lvl, child) +
      subqueries.map(c => childPretty(lvl, c)).mkString("")
  }

  def costEstimateImpl(ctx: EstimateContext, stats: Statistics) = {

    // find one-time-invoke subqueries (so we can charge them once, instead of for each
    // per-row invocation)

    // returns the dependent tuple positions for each subquery
    def makeSubqueryDepMap(e: SqlExpr): Map[Int, Seq[Int]] = {
      def findTuplePositions(e: SqlExpr): Seq[Int] = {
        val m = new ArrayBuffer[Int]
        topDownTraversal(e) {
          case TuplePosition(p, _) => m += p; false
          case _                   => true
        }
        m.toSeq
      }
      val m = new HashMap[Int, Seq[Int]]
      topDownTraversal(e) {
        case SubqueryPosition(p, args, _) =>
          m += ((p -> args.flatMap(findTuplePositions))); false
        case ExistsSubqueryPosition(p, args, _) =>
          m += ((p -> args.flatMap(findTuplePositions))); false
        case _ => true
      }
      m.toMap
    }

    val m = makeSubqueryDepMap(expr)
    val td = child.tupleDesc
    val ch = child.costEstimate(ctx, stats)

    val subCosts = subqueries.map(_.costEstimate(ctx, stats)).zipWithIndex.map {
      case (costPerInvocation, idx) =>
        m.get(idx).filterNot(_.isEmpty).map { pos =>
          // check any pos in agg ctx
          if (!pos.filter(p => td(p).vectorCtx).isEmpty) {
            //println(
            //  "subquery(%d): cpi = %f, effective_rows = %d".format(
            //    idx, costPerInvocation.cost, ch.rows * ch.rowsPerRow))
            costPerInvocation.cost * ch.rows * ch.rowsPerRow
          } else {
            //println(
            //  "subquery(%d): cpi = %f, effective_rows = %d".format(
            //    idx, costPerInvocation.cost, ch.rows))
            costPerInvocation.cost * ch.rows
          }
        }.getOrElse(costPerInvocation.cost)
    }.sum

    val stmtToCheck =
      if (canPushDownFilter) {
        ch.equivStmt.copy(
          filter = ch.equivStmt.filter.map(x => And(x, origExpr)).orElse(Some(origExpr)))
      } else {
        val newProjs = ch.equivStmt.projections.map {
          case ExprProj(expr, None, _) => ExprProj(expr, None)
          case ExprProj(_, Some(name), _) => ExprProj(FieldIdent(None, name), None)
        }
        SelectStmt(
          newProjs,
          Some(Seq(SubqueryRelationAST(ch.equivStmt, ctx.uniqueId()))),
          None,
          None,
          None,
          None)
      }

    val stmt = resolveCheck(stmtToCheck, ch.equivStmt.ctx.defns)

    val (_, r, rr, _, _) = extractCostFromDBStmt(stmt, ctx.defns.dbconn.get, Some(stats))

    // TODO: how do we cost filters?
    Estimate(ch.cost + subCosts, r, rr.getOrElse(1L), stmt, ch.seqScanInfo)
  }

  def emitCPPHelpers(cg: CodeGenerator, ctx: CodeGenContext) =
    (Seq(child) ++ subqueries).foreach(_.emitCPPHelpers(cg, ctx))

  def emitCPP(cg: CodeGenerator, ctx: CodeGenContext) = {
    cg.blockBegin("new local_filter_op(")
      cg.print(expr.constantFold.toCPP)
      cg.println(",")
      child.emitCPP(cg, ctx)
      cg.blockBegin(",{")
        subqueries.foreach(s => {s.emitCPP(cg, ctx); cg.println(",")})
      cg.blockEnd("}")
    cg.blockEnd(")")
  }
}

case class LocalTransform(
  /* (orig, opt orig proj name, translated) */
  trfms: Seq[Either[Int, (SqlExpr, Option[String], SqlExpr)]],

  /**
   * A query which represents what this node projects post transformation
   *
   * We need this because query structure is lost when we pick parts of it
   * apart, and it is very hard to reconstruct a semantically correct query
   * w/o having some assistance.
   *
   * At this point in the tree, we are more worried about cardinality estimations,
   * than database cost estimations, so we are ok with a plan which differs
   * significantly from how the encrypted plan will actually execute
   */
  origStmt: SelectStmt,

  child: PlanNode) extends PlanNode {

  assert(!trfms.isEmpty)

  def underlying = Some(child)

  def tupleDesc = {
    val td = child.tupleDesc
    trfms.map {
      case Left(pos) => td(pos)

      // TODO: allow for transforms to not remove vector context
      case Right((o, _, _)) =>
        val t = o.findCanonical.getType
        PosDesc(
          t.tpe, t.field.map(_.pos), PlainOnion,
          t.field.map(_.partOfPK).getOrElse(false), false)
    }
  }

  def pretty0(lvl: Int) = {
    val trfmObjs = trfms.map {
      case Left(p) => Left(p)
      case Right((o, _, e)) => Right((o.sql, e.sql))
    }
    "* LocalTransform(cost = " + _lastCostEstimate +
      ", transformation = " + trfmObjs + ")" + childPretty(lvl, child)
  }

  def costEstimateImpl(ctx: EstimateContext, stats: Statistics) = {
    // we assume these operations are cheap
    // TODO: this might not really be true - measure if it is
    val ch = child.costEstimate(ctx, stats)


    // we get hints from the plan generator about what our equivStmt
    // should look like
    ch.copy(equivStmt = origStmt)

    // this didn't really work below- the idea was to
    // project the inner child as a subquery- lots of little
    // corner cases make this hard to get right...

    //val newProjs = {
    //  val origProjs = ch.equivStmt.projections
    //  trfms.map {
    //    case Left(i) =>
    //      origProjs(i) match {
    //        case ExprProj(_, Some(a), _) =>
    //          ExprProj(FieldIdent(None, a), None)
    //        case StarProj(_) => throw new RuntimeException("unimpl")
    //        case e => e // TODO: not really right...
    //      }
    //    case Right((o, a, _)) => ExprProj(o, a)
    //  }
    //}

    //val newStmt =
    //  resolveCheck(
    //    SelectStmt(
    //      newProjs,
    //      Some(Seq(SubqueryRelationAST(ch.equivStmt, ctx.uniqueId()))),
    //      None,
    //      None,
    //      None,
    //      None),
    //    ctx.defns)

    //ch.copy(equivStmt = newStmt)
  }

  def emitCPPHelpers(cg: CodeGenerator, ctx: CodeGenContext) = child.emitCPPHelpers(cg, ctx)

  def emitCPP(cg: CodeGenerator, ctx: CodeGenContext) = {
    cg.blockBegin("new local_transform_op(")
      cg.print("{")
      trfms.foreach {
        case Left(i) =>
          cg.print("local_transform_op::trfm_desc(%d), ".format(i))
        case Right((orig, _, texpr)) =>
          val t = orig.findCanonical.getType
          if (t.tpe == UnknownType) {
            System.err.println("ERROR: " + orig)
            System.err.println("ERROR: " + orig.findCanonical)
          }
          cg.print("local_transform_op::trfm_desc(std::make_pair(%s, %s)), ".format(
            PosDesc(
              t.tpe, t.field.map(_.pos), PlainOnion,
              t.field.map(_.partOfPK).getOrElse(false), false).toCPP,
            texpr.toCPP
          ))
      }
      cg.println("},")
      child.emitCPP(cg, ctx)
    cg.blockEnd(")")
  }

  def allIdentityTransforms: Boolean = {
    trfms.flatMap(_.right.toOption).isEmpty
  }

  def identityTransformPositions: Seq[Int] = {
    assert(allIdentityTransforms)
    trfms.map(_.left.get)
  }
}

case class LocalGroupBy(
  keys: Seq[SqlExpr], origKeys: Seq[SqlExpr],
  filter: Option[SqlExpr], origFilter: Option[SqlExpr],
  child: PlanNode, subqueries: Seq[PlanNode]) extends PlanNode {

  {
    assert(keys.size == origKeys.size)
    assert(filter.isDefined == origFilter.isDefined)
  }

  def underlying = Some(child)

  protected def posVec: Seq[Int] =
    keys.map {
      case TuplePosition(i, _) => i
      case e => throw new RuntimeException("TODO: unsupported: " + e)
    }

  def tupleDesc = {
    val pos = posVec.toSet
    child.tupleDesc.zipWithIndex.map { case (td, idx) =>
      assert(!td.vectorCtx)
      if (pos.contains(idx)) {
        td
      } else {
        td.copy(vectorCtx = true)
      }
    }
  }

  def pretty0(lvl: Int) =
    "* LocalGroupBy(cost = " + _lastCostEstimate + ", keys = " + keys.map(_.sql).mkString(", ") + ", group_filter = " + filter.map(_.sql).getOrElse("none") + ")" + childPretty(lvl, child)

  def costEstimateImpl(ctx: EstimateContext, stats: Statistics) = {
    val ch = child.costEstimate(ctx, stats)

    // TODO: this is a bit of a hack
    val equivStmt =
      if (ch.equivStmt.groupBy.isEmpty) ch.equivStmt
      else {
        SelectStmt(
          ch.equivStmt.projections.map {
            case ExprProj(_, Some(alias), _) => ExprProj(FieldIdent(None, alias), None)
            case e @ ExprProj(_, None, _)    => e.copyWithContext(null).asInstanceOf[ExprProj]
            case _                           => throw new RuntimeException("cannot handle")
          },
          Some(Seq(SubqueryRelationAST(ch.equivStmt, ctx.uniqueId()))),
          None,
          None,
          None,
          None)
      }

    //println("origKeys: " + origKeys)

    val nameSet = origKeys.flatMap {
      case FieldIdent(q, n, ColumnSymbol(tbl, col, _, _), _) =>
        Seq(Left((tbl, col))) ++ (if (q.isDefined) Seq.empty else Seq(Right(n)))
      case FieldIdent(q, n, ProjectionSymbol(name, _, _), _) =>
        Seq(Right(name)) ++ (if (q.isDefined) Seq.empty else Seq(Right(n)))
    }.toSet

    //println("nameSet: " + nameSet)

    def wrapWithGroupConcat(e: SqlExpr) = GroupConcat(e, ",")

    val stmt =
      resolveCheck(
        equivStmt.copy(
          // need to rewrite projections

          // TODO: the rules for when to group_concat projects is kind of
          // fragile now
          projections = equivStmt.projections.map {
            case e @ ExprProj(fi @ FieldIdent(qual, name, _, _), projName, _) =>
              //println("e: " + e)
              if (projName.map(n => nameSet.contains(Right(n))).getOrElse(false)) {
                e
              } else {
                qual.flatMap { q =>
                  if (nameSet.contains(Left((q, name)))) Some(e) else None
                }.getOrElse(e.copy(expr = wrapWithGroupConcat(fi)))
              }

            case e @ ExprProj(expr, projName, _) =>
              if (projName.map(n => nameSet.contains(Right(n))).getOrElse(false)) {
                e
              } else {
                e.copy(expr = wrapWithGroupConcat(expr))
              }

            // TODO: what do we do here?
            case e => e

          },
          groupBy = Some(SqlGroupBy(origKeys, origFilter))),
        ch.equivStmt.ctx.defns)

    val (_, r, Some(rr), _, _) = extractCostFromDBStmt(stmt, ctx.defns.dbconn.get, Some(stats))
    // TODO: estimate the cost

    val groupByCost =
      CostConstants.secToPGUnit(CostConstants.GroupByRowsRate) * (r * rr).toDouble

    Estimate(ch.cost + groupByCost, r, rr, stmt, ch.seqScanInfo)
  }

  def emitCPPHelpers(cg: CodeGenerator, ctx: CodeGenContext) =
    (Seq(child) ++ subqueries).foreach(_.emitCPPHelpers(cg, ctx))

  def emitCPP(cg: CodeGenerator, ctx: CodeGenContext) = {
    val pos = posVec
    cg.blockBegin("new local_group_by(")
      cg.println("%s,".format(pos.map(_.toString).mkString("{", ", ", "}")))
      child.emitCPP(cg, ctx)
    cg.blockEnd(")")
  }
}

// this node is for when we have to remove the HAVING clause
// from a group by, but we still keep the group by.
case class LocalGroupFilter(filter: SqlExpr, origFilter: SqlExpr,
                            child: PlanNode, subqueries: Seq[PlanNode])
  extends PlanNode {
  def underlying = Some(child)
  def tupleDesc = child.tupleDesc
  def pretty0(lvl: Int) = {
    "* LocalGroupFilter(cost = " + _lastCostEstimate + ", filter = " + filter.sql + ")" +
      childPretty(lvl, child) +
      subqueries.map(c => childPretty(lvl, c)).mkString("")
  }

  def costEstimateImpl(ctx: EstimateContext, stats: Statistics) = {
    val ch = child.costEstimate(ctx, stats)

    // need to charge the subqueries
    val oneShot = collection.mutable.Seq.fill(subqueries.size)(false)
    topDownTraversal(filter) {
      case SubqueryPosition(p, args, _) =>
        if (args.isEmpty) oneShot(p) = true; false
      case ExistsSubqueryPosition(p, args, _) =>
        if (args.isEmpty) oneShot(p) = true; false
      case _ => true
    }

    val subCosts = subqueries.map(_.costEstimate(ctx, stats)).zipWithIndex.map {
      case (costPerInvocation, idx) =>
        if (oneShot(idx)) {
          costPerInvocation.cost
        } else {
          costPerInvocation.cost * ch.rows
        }
    }.sum

    // assume that the group keys are always available, for now
    assert(ch.equivStmt.groupBy.isDefined)
    val stmt =
      resolveCheck(
        ch.equivStmt.copy(
          groupBy =
            ch.equivStmt.groupBy.map(_.copy(having =
              ch.equivStmt.groupBy.get.having.map(x => And(x, origFilter)).orElse(Some(origFilter))))),
        ch.equivStmt.ctx.defns)

    //println(stmt.sqlFromDialect(PostgresDialect))

    val (_, r, Some(rr), _, _) = extractCostFromDBStmt(stmt, ctx.defns.dbconn.get, Some(stats))

    // TODO: how do we cost filters?
    // TODO: merge SSI from the subqueries also
    Estimate(ch.cost + subCosts, r, rr, stmt, ch.seqScanInfo)
  }

  def emitCPPHelpers(cg: CodeGenerator, ctx: CodeGenContext) =
    (Seq(child) ++ subqueries).foreach(_.emitCPPHelpers(cg, ctx))

  def emitCPP(cg: CodeGenerator, ctx: CodeGenContext) = {
    cg.blockBegin("new local_group_filter(")
      cg.print(filter.toCPP)
      cg.println(",")
      child.emitCPP(cg, ctx)
      cg.blockBegin(", {")
        subqueries.foreach(s => {s.emitCPP(cg, ctx); cg.println(",")})
      cg.blockEnd("}")
    cg.blockEnd(")")
  }
}

case class LocalOrderBy(sortKeys: Seq[(Int, OrderType)], child: PlanNode) extends PlanNode {
  {
    val td = child.tupleDesc
    // all sort keys must not be in vector context (b/c that would not make sense)
    sortKeys.foreach { case (idx, _) => assert(!td(idx).vectorCtx) }
  }

  def underlying = Some(child)
  def tupleDesc = child.tupleDesc
  def pretty0(lvl: Int) =
    "* LocalOrderBy(cost = " + _lastCostEstimate + ", keys = " + sortKeys.map(_._1.toString).toSeq + ")" + childPretty(lvl, child)

  def costEstimateImpl(ctx: EstimateContext, stats: Statistics) = {
    // do stuff

    child.costEstimate(ctx, stats)
  }

  def emitCPPHelpers(cg: CodeGenerator, ctx: CodeGenContext) = child.emitCPPHelpers(cg, ctx)

  def emitCPP(cg: CodeGenerator, ctx: CodeGenContext) = {
    cg.blockBegin("new local_order_by(")
      cg.println("%s,".format(
          sortKeys.map {
            case (i, o) => "std::make_pair(%d, %b)".format(i, o == DESC)
          }.mkString("{", ", ", "}")))
      child.emitCPP(cg, ctx)
    cg.blockEnd(")")
  }
}

case class LocalLimit(limit: Int, child: PlanNode) extends PlanNode {
  def underlying = Some(child)
  def tupleDesc = child.tupleDesc
  def pretty0(lvl: Int) =
    "* LocalLimit(cost = " + _lastCostEstimate + ", limit = " + limit + ")" + childPretty(lvl, child)

  def costEstimateImpl(ctx: EstimateContext, stats: Statistics) = {
    val ch = child.costEstimate(ctx, stats)
    // TODO: currently assuming everything must be completely materialized
    // before the limit. this isn't strictly true, but is true in the case
    // of TPC-H
    Estimate(ch.cost, math.min(limit, ch.rows), ch.rowsPerRow,
             ch.equivStmt, ch.seqScanInfo)
  }

  def emitCPPHelpers(cg: CodeGenerator, ctx: CodeGenContext) = child.emitCPPHelpers(cg, ctx)

  def emitCPP(cg: CodeGenerator, ctx: CodeGenContext) = {
    cg.blockBegin("new local_limit(%d, ".format(limit))
      child.emitCPP(cg, ctx)
    cg.blockEnd(")")
  }
}

case class LocalDecrypt(positions: Seq[Int], child: PlanNode) extends PlanNode {
  def underlying = Some(child)
  def tupleDesc = {
    val td = child.tupleDesc
    assert(positions.filter(p => td(p).onion.isPlain).isEmpty)
    assert(positions.filter(p => td(p).onion match {
            case _: HomRowDescOnion => true
            case _ => false
           }).isEmpty)
    val p0 = positions.toSet
    td.zipWithIndex.map {
      case (pd, i) if p0.contains(i) => pd.copy(onion = PlainOnion)
      case (pd, _)                   => pd
    }
  }
  def pretty0(lvl: Int) =
    "* LocalDecrypt(cost = " + _lastCostEstimate + ", positions = " + positions + ")" + childPretty(lvl, child)

  def costEstimateImpl(ctx: EstimateContext, stats: Statistics) = {
    val ch = child.costEstimate(ctx, stats)
    val td = child.tupleDesc
    def costPos(p: Int): Double = {
      td(p) match {
        case PosDesc(_, _, HomGroupOnion(tbl, grp), _, _) =>
          // (tbl, grp) is a GLOBAL id here
          val c = CostConstants.secToPGUnit(CostConstants.DecryptCostMap(Onions.HOM))
          c * ch.rows.toDouble // for now assume that each agg group needs to do 1 decryption

        case PosDesc(_, _, RegularOnion(o), _, vecCtx) =>
          val c = CostConstants.secToPGUnit(CostConstants.DecryptCostMap(o))
          c * ch.rows.toDouble * (if (vecCtx) ch.rowsPerRow.toDouble else 1.0)

        case _ =>
          throw new RuntimeException("should not happen")
      }
    }
    val contrib = positions.map(costPos).sum
    ch.copy(cost = ch.cost + contrib)
  }

  def emitCPPHelpers(cg: CodeGenerator, ctx: CodeGenContext) = child.emitCPPHelpers(cg, ctx)

  def emitCPP(cg: CodeGenerator, ctx: CodeGenContext) = {
    cg.blockBegin("new local_decrypt_op(")
      cg.println("%s,".format(
          positions.map(_.toString).mkString("{", ", ", "}")))
      child.emitCPP(cg, ctx)
    cg.blockEnd(")")
  }
}

case class LocalEncrypt(
  /* (tuple pos to enc, onion to enc) */
  positions: Seq[(Int, OnionType)],
  child: PlanNode) extends PlanNode {
  def underlying = Some(child)
  def tupleDesc = {
    val td = child.tupleDesc
    assert(positions.filter { case (p, _) => !td(p).onion.isPlain || td(p).vectorCtx }.isEmpty)
    assert(positions.filter(p => p._2 match {
            case _: HomRowDescOnion => true
            case _ => false
           }).isEmpty)
    val p0 = positions.toMap
    td.zipWithIndex.map {
      case (pd, i) if p0.contains(i) => pd.encryptDesc(p0(i))
      case (pd, _)                   => pd
    }
  }
  def pretty0(lvl: Int) =
    "* LocalEncrypt(cost = " + _lastCostEstimate + ", positions = " + positions + ")" + childPretty(lvl, child)

  def costEstimateImpl(ctx: EstimateContext, stats: Statistics) = {
    val ch = child.costEstimate(ctx, stats)
    val td = child.tupleDesc
    def costPos(p: (Int, OnionType)): Double = {
      p._2 match {
        case RegularOnion(o) =>
          val c = CostConstants.secToPGUnit(CostConstants.EncryptCostMap(o))
          c * ch.rows.toDouble

        case _ =>
          throw new RuntimeException("should not happen")
      }
    }
    val contrib = positions.map(costPos).sum
    ch.copy(cost = ch.cost + contrib)
  }

  def emitCPPHelpers(cg: CodeGenerator, ctx: CodeGenContext) = child.emitCPPHelpers(cg, ctx)

  def emitCPP(cg: CodeGenerator, ctx: CodeGenContext) = {
    val td = tupleDesc
    cg.print("new local_encrypt_op({")
    CollectionUtils.foreachWithAllButLastAction(positions)({ case (p, o) =>
      val pd = td(p)
      cg.print("std::pair<size_t, db_column_desc>(%d, %s)".format(p, pd.toCPP))
    })(() => cg.print(", "))
    cg.print("}, ")
    child.emitCPP(cg, ctx)
    cg.print(")")
  }
}
